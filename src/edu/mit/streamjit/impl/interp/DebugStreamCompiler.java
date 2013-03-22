package edu.mit.streamjit.impl.interp;

import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.api.Portal;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.StreamVisitor;
import edu.mit.streamjit.api.Splitjoin;
import edu.mit.streamjit.api.Pipeline;
import edu.mit.streamjit.api.Joiner;
import edu.mit.streamjit.api.Splitter;
import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Rate;
import edu.mit.streamjit.impl.common.ConnectWorkersVisitor;
import edu.mit.streamjit.impl.common.Portals;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.interp.Interpreter.IOChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A StreamCompiler that interprets the stream graph on the thread that calls
 * CompiledStream.put(). This compiler performs extra checks to verify filters
 * conform to their rate declarations. The CompiledStream returned from the
 * compile() method synchronizes offer() and poll() such that only up to one
 * element is being offered or polled at once. As its name suggests, this
 * compiler is intended for debugging purposes; it is unlikely to provide good
 * performance.
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 11/20/2012
 */
public class DebugStreamCompiler implements StreamCompiler {
	@Override
	public <I, O> CompiledStream<I, O> compile(OneToOneElement<I, O> stream) {
		ConnectWorkersVisitor cpwv = new ConnectWorkersVisitor(new ChannelFactory() {
			@Override
			public <E> Channel<E> makeChannel(Worker<?, E> upstream, Worker<E, ?> downstream) {
				return new DebugChannel<>();
			}
		});
		stream.visit(cpwv);
		Worker<I, ?> source = (Worker<I, ?>)cpwv.getSource();
		DebugChannel<I> head = new DebugChannel<>();
		Workers.getInputChannels(source).add(head);
		Worker<?, O> sink = (Worker<?, O>)cpwv.getSink();
		DebugChannel<O> tail = new DebugChannel<>();
		Workers.getOutputChannels(sink).add(tail);

		List<MessageConstraint> constraints = MessageConstraint.findConstraints(source);
		Set<Portal<?>> portals = new HashSet<>();
		for (MessageConstraint mc : constraints)
			portals.add(mc.getPortal());
		for (Portal<?> portal : portals)
			Portals.setConstraints(portal, constraints);

		return new DebugCompiledStream<>(head, tail, source, sink, constraints);
	}

	/**
	 * This CompiledStream synchronizes offer() and poll(), so it can use
	 * unsynchronized Channels.
	 *
	 * TODO: should we use bounded buffers here?
	 * @param <I> the type of input data elements
	 * @param <O> the type of output data elements
	 */
	private static class DebugCompiledStream<I, O> extends AbstractCompiledStream<I, O> {
		private final Interpreter interpreter;
		DebugCompiledStream(Channel<? super I> head, Channel<? extends O> tail, Worker<?, ?> source, Worker<?, ?> sink, List<MessageConstraint> constraints) {
			super(head, tail);
			this.interpreter = new Interpreter(Workers.getAllWorkersInGraph(source), constraints) {
				@Override
				protected void afterFire(Worker<?, ?> worker) {
					checkRatesAfterFire(worker);
				}
			};
		}

		@Override
		public synchronized boolean offer(I input) {
			boolean ret = super.offer(input);
			interpreter.interpret();
			return ret;
		}

		@Override
		public synchronized O poll() {
			return super.poll();
		}

		@Override
		protected synchronized void doDrain() {
			//Most implementations of doDrain() hand off to another thread to
			//avoid blocking in drain(), but we only have one thread.
			interpreter.interpret();
			//We need to see if any elements were left undrained.
			assert interpreter.getChannels().size() == 2 : interpreter.getChannels();
			Channel<?> inputChannel = null, outputChannel = null;
			for (IOChannel c : interpreter.getChannels().values())
				if (c.isInput())
					inputChannel = c.getChannel();
				else
					outputChannel = c.getChannel();
			UndrainedVisitor v = new UndrainedVisitor(inputChannel, outputChannel);
			finishedDraining(v.isFullyDrained());
		}

		/**
		 * Checks the given worker (which has just fired)'s rate declarations
		 * against its actual behavior.
		 */
		private <I, O> void checkRatesAfterFire(Worker<I, O> worker) {
			List<Channel<? extends I>> inputChannels = Workers.getInputChannels(worker);
			List<Rate> popRates = worker.getPopRates();
			List<Rate> peekRates = worker.getPeekRates();
			for (int i = 0; i < inputChannels.size(); ++i) {
				Rate peek = peekRates.get(i), pop = popRates.get(i);
				//All channels we create are DebugChannels, so this is safe.
				DebugChannel<? extends I> channel = (DebugChannel<? extends I>)inputChannels.get(i);
				int peekIndex = channel.getMaxPeekIndex();
				if (peek.min() != Rate.DYNAMIC && peekIndex+1 < peek.min() ||
						peek.max() != Rate.DYNAMIC && peekIndex+1 > peek.max())
					throw new AssertionError(String.format("%s: Peek rate %s but peeked at index %d on channel %d", worker, peek, peekIndex, i));
				int popCount = channel.getPopCount();
				if (pop.min() != Rate.DYNAMIC && popCount < pop.min() ||
						pop.max() != Rate.DYNAMIC && popCount > pop.max())
					throw new AssertionError(String.format("%s: Pop rate %s but popped %d elements from channel %d", worker, peek, popCount, i));
				channel.resetStatistics();
			}

			List<Channel<? super O>> outputChannels = Workers.getOutputChannels(worker);
			List<Rate> pushRates = worker.getPushRates();
			for (int i = 0; i < outputChannels.size(); ++i) {
				Rate push = pushRates.get(i);
				//All channels we create are DebugChannels, so this is safe.
				DebugChannel<? super O> channel = (DebugChannel<? super O>)outputChannels.get(i);
				int pushCount = channel.getPushCount();
				if (push.min() != Rate.DYNAMIC && pushCount < push.min() ||
						push.max() != Rate.DYNAMIC && pushCount > push.max())
					throw new AssertionError(String.format("%s: Push rate %s but pushed %d elements onto channel %d", worker, push, pushCount, i));
				channel.resetStatistics();
			}
		}
	}

	/**
	 * Checks if a stream fully drained or not.
	 *
	 * TODO: check for pending messages?
	 */
	private static class UndrainedVisitor extends StreamVisitor {
		private final Channel<?> streamOutput;
		private boolean fullyDrained = true;
		/**
		 * Constructs a new UndrainedVisitor for a stream with the given input
		 * and output channels.
		 */
		UndrainedVisitor(Channel<?> streamInput, Channel<?> streamOutput) {
			this.streamOutput = streamOutput;
			if (!streamInput.isEmpty())
				fullyDrained = false;
		}

		public boolean isFullyDrained() {
			return fullyDrained;
		}

		private void visitWorker(Worker<?, ?> worker) {
			//Every input channel except for the very first in the stream is an
			//output channel of some other worker, and we checked the first one
			//in the constructor, so we only need to check output channels here.
			for (Channel<?> c : Workers.getOutputChannels(worker))
				//Ignore the stream's final output, as it doesn't count as
				//"undrained" even if it hasn't been picked up yet.
				if (c != streamOutput && !c.isEmpty())
					fullyDrained = false;
		}
		@Override
		public void visitFilter(Filter<?, ?> filter) {
			visitWorker(filter);
		}
		@Override
		public boolean enterPipeline(Pipeline<?, ?> pipeline) {
			//Enter the pipeline only if we haven't found undrained data yet.
			return fullyDrained;
		}
		@Override
		public void exitPipeline(Pipeline<?, ?> pipeline) {
		}
		@Override
		public boolean enterSplitjoin(Splitjoin<?, ?> splitjoin) {
			//Enter the splitjoin only if we haven't found undrained data yet.
			return fullyDrained;
		}
		@Override
		public void visitSplitter(Splitter<?, ?> splitter) {
			visitWorker(splitter);
		}
		@Override
		public boolean enterSplitjoinBranch(OneToOneElement<?, ?> element) {
			//Enter the branch only if we haven't found undrained data yet.
			return fullyDrained;
		}
		@Override
		public void exitSplitjoinBranch(OneToOneElement<?, ?> element) {
		}
		@Override
		public void visitJoiner(Joiner<?, ?> joiner) {
			visitWorker(joiner);
		}
		@Override
		public void exitSplitjoin(Splitjoin<?, ?> splitjoin) {
		}
		@Override
		public void beginVisit() {
		}
		@Override
		public void endVisit() {
		}
	}
}