package edu.mit.streamjit.impl.compiler;

import static com.google.common.base.Preconditions.*;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Identity;
import edu.mit.streamjit.api.Joiner;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Rate;
import edu.mit.streamjit.api.RoundrobinJoiner;
import edu.mit.streamjit.api.RoundrobinSplitter;
import edu.mit.streamjit.api.Splitjoin;
import edu.mit.streamjit.api.Splitter;
import edu.mit.streamjit.api.StatefulFilter;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.ConnectWorkersVisitor;
import edu.mit.streamjit.impl.common.IOInfo;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.compiler.insts.ArrayLoadInst;
import edu.mit.streamjit.impl.compiler.insts.ArrayStoreInst;
import edu.mit.streamjit.impl.compiler.insts.BinaryInst;
import edu.mit.streamjit.impl.compiler.insts.CallInst;
import edu.mit.streamjit.impl.compiler.insts.Instruction;
import edu.mit.streamjit.impl.compiler.insts.JumpInst;
import edu.mit.streamjit.impl.compiler.insts.LoadInst;
import edu.mit.streamjit.impl.compiler.insts.NewArrayInst;
import edu.mit.streamjit.impl.compiler.insts.ReturnInst;
import edu.mit.streamjit.impl.compiler.insts.StoreInst;
import edu.mit.streamjit.impl.compiler.types.MethodType;
import edu.mit.streamjit.impl.compiler.types.RegularType;
import edu.mit.streamjit.impl.interp.Channel;
import edu.mit.streamjit.impl.interp.ChannelFactory;
import edu.mit.streamjit.impl.interp.EmptyChannel;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 4/24/2013
 */
public final class Compiler {
	/**
	 * A counter used to generate package names unique to a given machine.
	 */
	private static final AtomicInteger PACKAGE_NUMBER = new AtomicInteger();
	private final Set<Worker<?, ?>> workers;
	private final Configuration config;
	private final int maxNumCores;
	private final ImmutableSet<IOInfo> ioinfo;
	private final Worker<?, ?> firstWorker, lastWorker;
	private final Map<Worker<?, ?>, WorkerData> workerData;
	private final String packagePrefix;
	private final Module module = new Module();
	private final Klass blobKlass;
	public Compiler(Set<Worker<?, ?>> workers, Configuration config, int maxNumCores) {
		this.workers = workers;
		this.config = config;
		this.maxNumCores = maxNumCores;
		this.ioinfo = IOInfo.create(workers);
		this.workerData = new IdentityHashMap<>(workers.size());

		//We can only have one first and last worker, though they can have
		//multiple inputs/outputs.
		Worker<?, ?> firstWorker = null, lastWorker = null;
		for (IOInfo io : ioinfo)
			if (io.isInput())
				if (firstWorker == null)
					firstWorker = io.downstream();
				else
					checkArgument(firstWorker == io.downstream(), "two input workers");
			else
				if (lastWorker == null)
					lastWorker = io.upstream();
				else
					checkArgument(lastWorker == io.upstream(), "two output workers");
		assert firstWorker != null : "Can't happen! No first worker?";
		assert lastWorker != null : "Can't happen! No last worker?";
		this.firstWorker = firstWorker;
		this.lastWorker = lastWorker;

		//We require that all rates of workers in our set are fixed, except for
		//the output rates of the last worker.
		for (Worker<?, ?> w : workers) {
			for (Rate r : w.getPeekRates())
				checkArgument(r.isFixed());
			for (Rate r : w.getPopRates())
				checkArgument(r.isFixed());
			if (w != lastWorker)
				for (Rate r : w.getPushRates())
					checkArgument(r.isFixed());
		}

		//We don't support messaging.
		List<MessageConstraint> constraints = MessageConstraint.findConstraints(firstWorker);
		for (MessageConstraint c : constraints) {
			checkArgument(!workers.contains(c.getSender()));
			checkArgument(!workers.contains(c.getRecipient()));
		}

		this.packagePrefix = "compiler"+PACKAGE_NUMBER.getAndIncrement()+".";
		this.blobKlass = new Klass(packagePrefix + "Blob",
				module.getKlass(Object.class),
				Collections.singletonList(module.getKlass(Blob.class)),
				module);
	}

	public Blob compile() {
		for (Worker<?, ?> w : workers)
			buildWorkerData(w);
		addBlobPlumbing();
		blobKlass.dump(new PrintWriter(System.out, true));
		return instantiateBlob();
	}

	private void buildWorkerData(Worker<?, ?> worker) {
		WorkerData data = new WorkerData(worker);
		workerData.put(worker, data);
		int id = Workers.getIdentifier(worker);
		Klass workerKlass = module.getKlass(worker.getClass());

		//Build the new fields.
		for (Field f : workerKlass.fields()) {
			java.lang.reflect.Field rf = f.getBackingField();
			Set<Modifier> modifiers = EnumSet.of(Modifier.PRIVATE, Modifier.STATIC);
			//We can make the new field final if the original field is final or
			//if the worker isn't stateful.
			if (f.modifiers().contains(Modifier.FINAL) || !(worker instanceof StatefulFilter))
				modifiers.add(Modifier.FINAL);

			Field nf = new Field(f.getType().getFieldType(),
					"w"+id+"$"+f.getName(),
					modifiers,
					blobKlass);
			data.fields.put(f, nf);

			try {
				rf.setAccessible(true);
				Object value = rf.get(worker);
				data.fieldValues.put(f, value);
			} catch (IllegalAccessException ex) {
				//Either setAccessible will succeed or we'll throw a
				//SecurityException, so we'll never get here.
				throw new AssertionError("Can't happen!", ex);
			}
		}

		makeWorkMethod(worker);
	}

	/**
	 * Make the work method for the given worker.  We actually make two methods
	 * here: first we make a copy with a dummy receiver argument, just to have a
	 * copy to work with.  After remapping every use of that receiver (remapping
	 * field accesses to the worker's static fields, remapping JIT-hooks to
	 * their implementations, and remapping utility methods in the worker class
	 * recursively), we then create the actual work method without the receiver
	 * argument.
	 * @param worker
	 */
	private void makeWorkMethod(Worker<?, ?> worker) {
		WorkerData data = workerData.get(worker);
		int id = Workers.getIdentifier(worker);
		int numInputs = getNumInputs(worker);
		int numOutputs = getNumOutputs(worker);
		Klass workerKlass = module.getKlass(worker.getClass());
		Method oldWork = workerKlass.getMethod("work", module.types().getMethodType(void.class, worker.getClass()));
		oldWork.resolve();

		MethodType workMethodType = makeWorkMethodType(worker);
		MethodType rworkMethodType = workMethodType.prependArgument(module.types().getRegularType(workerKlass));
		Method newWork = new Method("rwork"+id, rworkMethodType, EnumSet.of(Modifier.PRIVATE, Modifier.STATIC), blobKlass);
		newWork.arguments().get(0).setName("dummyReceiver");

		Map<Value, Value> vmap = new IdentityHashMap<>();
		vmap.put(oldWork.arguments().get(0), newWork.arguments().get(0));
		Cloning.cloneMethod(oldWork, newWork, vmap);

		data.popCount = new Field(module.types().getRegularType(numInputs > 1 ? int[].class : int.class),
				"w"+id+"$popCount",
				EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.SYNTHETIC),
				blobKlass);
		data.pushCount = new Field(module.types().getRegularType(numOutputs > 1 ? int[].class : int.class),
				"w"+id+"$pushCount",
				EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.SYNTHETIC),
				blobKlass);

		BasicBlock entryBlock = new BasicBlock(module, "entry");
		newWork.basicBlocks().add(0, entryBlock);

		Value popCountInitValue;
		if (numInputs > 1) {
			NewArrayInst newArrayInst = new NewArrayInst(module.types().getArrayType(int[].class), module.constants().getConstant(numInputs));
			newArrayInst.setName("popCountArray");
			entryBlock.instructions().add(newArrayInst);
			popCountInitValue = newArrayInst;
		} else
			popCountInitValue = module.constants().getConstant(0);
		StoreInst popCountInit = new StoreInst(data.popCount, popCountInitValue);
		popCountInit.setName("popCountInit");
		entryBlock.instructions().add(popCountInit);

		Value pushCountInitValue;
		if (numOutputs > 1) {
			NewArrayInst newArrayInst = new NewArrayInst(module.types().getArrayType(int[].class), module.constants().getConstant(numOutputs));
			newArrayInst.setName("pushCountArray");
			entryBlock.instructions().add(newArrayInst);
			pushCountInitValue = newArrayInst;
		} else
			pushCountInitValue = module.constants().getConstant(0);
		StoreInst pushCountInit = new StoreInst(data.pushCount, pushCountInitValue);
		pushCountInit.setName("pushCountInit");
		entryBlock.instructions().add(pushCountInit);

		entryBlock.instructions().add(new JumpInst(newWork.basicBlocks().get(1)));

		//Remap stuff in rwork.
		for (BasicBlock b : newWork.basicBlocks())
			for (Instruction i : ImmutableList.copyOf(b.instructions()))
				if (Iterables.contains(i.operands(), newWork.arguments().get(0)))
					remapEliminiatingReceiver(i, data);

		//At this point, we've replaced all uses of the dummy receiver argument.
		assert newWork.arguments().get(0).uses().isEmpty();
	}

	private MethodType makeWorkMethodType(Worker<?, ?> worker) {
		RegularType inputParam = module.types().getArrayType(
				Object.class,
				worker instanceof Joiner ? 2 : 1);
		RegularType outputParam;
		if (worker == lastWorker)
			outputParam = worker instanceof Splitter ?
					module.types().getArrayType(Channel.class, 1) :
					module.types().getRegularType(Channel.class);
		else
			outputParam = module.types().getArrayType(Object.class,	worker instanceof Splitter ? 2 : 1);
		return module.types().getMethodType(module.types().getVoidType(), inputParam, outputParam);
	}

	private void remapEliminiatingReceiver(Instruction inst, WorkerData data) {
		BasicBlock block = inst.getParent();
		Method rwork = inst.getParent().getParent();
		if (inst instanceof CallInst) {
			CallInst ci = (CallInst)inst;
			Method method = ci.getMethod();
			Klass filterKlass = module.getKlass(Filter.class);
			Klass splitterKlass = module.getKlass(Splitter.class);
			Klass joinerKlass = module.getKlass(Joiner.class);
			Method pop1Filter = filterKlass.getMethod("pop", module.types().getMethodType(Object.class, Filter.class));
			assert pop1Filter != null;
			Method pop1Splitter = splitterKlass.getMethod("pop", module.types().getMethodType(Object.class, Splitter.class));
			assert pop1Splitter != null;
			Method push1Filter = filterKlass.getMethod("push", module.types().getMethodType(void.class, Filter.class, Object.class));
			assert push1Filter != null;
			Method push1Joiner = joinerKlass.getMethod("push", module.types().getMethodType(void.class, Joiner.class, Object.class));
			assert push1Joiner != null;
			Method pop2 = joinerKlass.getMethod("pop", module.types().getMethodType(Object.class, Joiner.class, int.class));
			assert pop2 != null;
			Method push2 = splitterKlass.getMethod("push", module.types().getMethodType(void.class, Splitter.class, int.class, Object.class));
			assert push2 != null;
			Method inputs = joinerKlass.getMethod("inputs", module.types().getMethodType(int.class, Joiner.class));
			assert inputs != null;
			Method outputs = splitterKlass.getMethod("outputs", module.types().getMethodType(int.class, Splitter.class));
			assert outputs != null;

			Method channelPush = module.getKlass(Channel.class).getMethod("push", module.types().getMethodType(void.class, Channel.class, Object.class));
			assert channelPush != null;

			if (method.equals(pop1Filter) || method.equals(pop1Splitter)) {
				LoadInst popCount = new LoadInst(data.popCount);
				ArrayLoadInst item = new ArrayLoadInst(rwork.arguments().get(1), popCount);
				item.setName("poppedItem");
				BinaryInst popCountPlusOne = new BinaryInst(popCount, BinaryInst.Operation.ADD, module.constants().getSmallestIntConstant(1));
				StoreInst updatePopCount = new StoreInst(data.popCount, popCountPlusOne);
				inst.replaceInstWithInsts(item, popCount, item, popCountPlusOne, updatePopCount);
			} else if ((method.equals(push1Filter) || method.equals(push1Joiner)) && data.worker != lastWorker) {
				Value item = ci.getArgument(1);
				LoadInst pushCount = new LoadInst(data.pushCount);
				ArrayStoreInst store = new ArrayStoreInst(rwork.arguments().get(2), pushCount, item);
				BinaryInst pushCountPlusOne = new BinaryInst(pushCount, BinaryInst.Operation.ADD, module.constants().getSmallestIntConstant(1));
				StoreInst updatePushCount = new StoreInst(data.popCount, pushCountPlusOne);
				inst.replaceInstWithInsts(store, pushCount, store, pushCountPlusOne, updatePushCount);
			} else if ((method.equals(push1Filter) || method.equals(push1Joiner)) && data.worker == lastWorker) {
				Value item = ci.getArgument(1);
				CallInst pushOntoChannel = new CallInst(channelPush, rwork.arguments().get(2), item);
				inst.replaceInstWithInst(pushOntoChannel);
			} else if (method.equals(pop2)) {
				LoadInst popCountArray = new LoadInst(data.popCount);
				ArrayLoadInst popCount = new ArrayLoadInst(popCountArray, ci.getArgument(1));
				ArrayLoadInst inputSelect = new ArrayLoadInst(rwork.arguments().get(1), ci.getArgument(1));
				ArrayLoadInst item = new ArrayLoadInst(inputSelect, popCount);
				item.setName("poppedItem");
				BinaryInst popCountPlusOne = new BinaryInst(popCount, BinaryInst.Operation.ADD, module.constants().getSmallestIntConstant(1));
				ArrayStoreInst updatePopCount = new ArrayStoreInst(popCountArray, ci.getArgument(1), popCountPlusOne);
				inst.replaceInstWithInsts(item, popCountArray, popCount, inputSelect, item, popCountPlusOne, updatePopCount);
			} else if (method.equals(push2) && data.worker != lastWorker) {
				Value item = ci.getArgument(2);
				LoadInst pushCountArray = new LoadInst(data.pushCount);
				ArrayLoadInst pushCount = new ArrayLoadInst(pushCountArray, ci.getArgument(1));
				ArrayLoadInst outputSelect = new ArrayLoadInst(rwork.arguments().get(2), ci.getArgument(1));
				ArrayStoreInst store = new ArrayStoreInst(outputSelect, pushCount, item);
				BinaryInst pushCountPlusOne = new BinaryInst(pushCount, BinaryInst.Operation.ADD, module.constants().getSmallestIntConstant(1));
				ArrayStoreInst updatePushCount = new ArrayStoreInst(pushCountArray, ci.getArgument(1), pushCountPlusOne);
				inst.replaceInstWithInsts(store, pushCountArray, pushCount, outputSelect, store, pushCountPlusOne, updatePushCount);
			} else if (method.equals(push2) && data.worker == lastWorker) {
				Value item = ci.getArgument(2);
				ArrayLoadInst outputSelect = new ArrayLoadInst(rwork.arguments().get(2), ci.getArgument(1));
				CallInst pushOntoChannel = new CallInst(channelPush, outputSelect, item);
				inst.replaceInstWithInsts(pushOntoChannel, outputSelect, pushOntoChannel);
			} else if (method.equals(outputs)) {
				inst.replaceInstWithValue(module.constants().getSmallestIntConstant(getNumOutputs(data.worker)));
			} else if (method.equals(inputs)) {
				inst.replaceInstWithValue(module.constants().getSmallestIntConstant(getNumInputs(data.worker)));
			} else
				throw new AssertionError(inst);
		} else if (inst instanceof LoadInst) {
			LoadInst li = (LoadInst)inst;
			LoadInst replacement = new LoadInst(data.fields.get(li.getField()));
			li.replaceInstWithInst(replacement);
		} else
			throw new AssertionError("Couldn't eliminate reciever: "+inst);
	}

	private int getNumInputs(Worker<?, ?> w) {
		return Workers.getInputChannels(w).size();
	}

	private int getNumOutputs(Worker<?, ?> w) {
		return Workers.getOutputChannels(w).size();
	}

	/**
	 * Adds required plumbing code to the blob class, such as the ctor and the
	 * implementations of the Blob methods.
	 */
	private void addBlobPlumbing() {
		//ctor
		Method init = new Method("<init>",
				module.types().getMethodType(module.types().getType(blobKlass)),
				EnumSet.noneOf(Modifier.class),
				blobKlass);
		BasicBlock b = new BasicBlock(module);
		init.basicBlocks().add(b);
		Method objCtor = module.getKlass(Object.class).getMethods("<init>").iterator().next();
		b.instructions().add(new CallInst(objCtor));
		b.instructions().add(new ReturnInst(module.types().getVoidType()));
		//TODO: other Blob interface methods
	}

	private Blob instantiateBlob() {
		ModuleClassLoader mcl = new ModuleClassLoader(module);
		try {
			Class<?> blobClass = mcl.loadClass(blobKlass.getName());
			Constructor<?> ctor = blobClass.getDeclaredConstructor();
			ctor.setAccessible(true);
			return (Blob)ctor.newInstance();
		} catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException | InstantiationException ex) {
			throw new AssertionError(ex);
		}
	}

	/**
	 * WorkerData contains worker-specific information.
	 */
	private static final class WorkerData {
		private final Worker<?, ?> worker;
		/**
		 * The method corresponding to this worker's work method.  May be null
		 * if the method hasn't been created yet.
		 */
		private Method workMethod;
		/**
		 * Maps fields in the worker class to fields in the blob class for this
		 * particular worker.
		 */
		private final Map<Field, Field> fields = new IdentityHashMap<>();
		/**
		 * Maps final fields in the worker class to their actual values, for
		 * inlining or blob initialization purposes.
		 */
		private final Map<Field, Object> fieldValues = new IdentityHashMap<>();
		private Field popCount, pushCount;
		private WorkerData(Worker<?, ?> worker) {
			this.worker = worker;
		}
	}

	public static void main(String[] args) {
		OneToOneElement<Integer, Integer> graph = new Splitjoin<>(new RoundrobinSplitter<Integer>(), new RoundrobinJoiner<Integer>(), new Identity<Integer>(), new Identity<Integer>());
		ConnectWorkersVisitor cwv = new ConnectWorkersVisitor(new ChannelFactory() {
			@Override
			public <E> Channel<E> makeChannel(Worker<?, E> upstream, Worker<E, ?> downstream) {
				return new EmptyChannel<>();
			}
		});
		graph.visit(cwv);
		Set<Worker<?, ?>> workers = Workers.getAllWorkersInGraph(cwv.getSource());
		Configuration config = Configuration.builder().build();
		int maxNumCores = 1;
		Compiler compiler = new Compiler(workers, config, maxNumCores);
		Blob blob = compiler.compile();
		blob.getCoreCount();
	}
}
