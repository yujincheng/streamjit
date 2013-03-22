package edu.mit.streamjit.impl.interp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import edu.mit.streamjit.api.IllegalStreamGraphException;
import edu.mit.streamjit.api.Rate;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Interpreter interprets a section of a stream graph.  An Interpreter's
 * interpret() method will run a pull schedule on the "bottom-most" filters in
 * the section (filters that are not predecessor of other filters in the blob),
 * firing them as many times as possible.
 *
 * An Interpreter has input and output channels, identified by opaque Token
 * objects.  An input channel is any channel from a worker not in the
 * Interpreter's stream graph section to one inside it, and an output channel,
 * vice versa.  The Interpreter expects these channels to already be installed
 * on the workers.
 *
 * To communicate between two Interpreter instances on the same machine, use
 * a synchronized channel implementation to connect outputs of one interpreter
 * to the inputs of the other.
 *
 * To communicate between interpreter instances on different machines, have a
 * thread on one machine poll() on output channels (if you can afford one thread
 * per output, use a blocking channel implementation) and send that data to the
 * other machine, then use threads on the other machine to read the data and
 * offer() it to input channels. It's tempting to put the send/receive in the
 * channel implementations themselves, but this may block the interpreter on
 * I/O, and makes implementing peek() on the receiving side tricky.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 3/22/2013
 */
public class Interpreter {
	private final ImmutableSet<Worker<?, ?>> workers, sinks;
	private final ImmutableMap<Token, IOChannel> ioChannels;
	/**
	 * Maps workers to all constraints of which they are recipients.
	 */
	private final Map<Worker<?, ?>, List<MessageConstraint>> constraintsForRecipient = new IdentityHashMap<>();
	public Interpreter(Iterable<Worker<?, ?>> workersIter, Iterable<MessageConstraint> constraintsIter) {
		this.workers = ImmutableSet.copyOf(workersIter);
		//Sinks are any filter that isn't a predecessor of another filter.
		Set<Worker<?, ?>> sinksAccum = new HashSet<>();
		Iterables.addAll(sinksAccum, workers);
		for (Worker<?, ?> worker : workers)
			sinksAccum.removeAll(Workers.getAllPredecessors(worker));
		this.sinks = ImmutableSet.copyOf(sinksAccum);

		for (MessageConstraint mc : constraintsIter)
			if (this.workers.contains(mc.getSender()) != this.workers.contains(mc.getRecipient()))
				throw new IllegalArgumentException("Constraint crosses interpreter boundary: "+mc);
		for (MessageConstraint constraint : constraintsIter) {
			Worker<?, ?> recipient = constraint.getRecipient();
			List<MessageConstraint> constraintList = constraintsForRecipient.get(recipient);
			if (constraintList == null) {
				constraintList = new ArrayList<>();
				constraintsForRecipient.put(recipient, constraintList);
			}
			constraintList.add(constraint);
		}

		ImmutableMap.Builder<Token, IOChannel> ioChannelsBuilder = ImmutableMap.builder();
		int channelNo = 0;
		for (Worker<?, ?> worker : workers) {
			List<Channel<?>> inChannels = ImmutableList.<Channel<?>>builder().addAll(Workers.getInputChannels(worker)).build();
			ImmutableList<Worker<?, ?>> preds = ImmutableList.<Worker<?, ?>>builder().addAll(Workers.getPredecessors(worker)).build();
			for (int i = 0; i < inChannels.size(); ++i) {
				//Get the predecessor, or if the input channel is the actual
				//input to the stream graph, null.
				Worker<?, ?> pred = Iterables.get(preds, i, null);
				//Null is "not in stream graph section" (so this works).
				if (!workers.contains(pred)) {
					Token token = new Token(channelNo++);
					Channel<?> channel = inChannels.get(i);
					IOChannel iochannel = new IOChannel(channel, pred, worker, true);
					ioChannelsBuilder.put(token, iochannel);
				}
			}

			List<Channel<?>> outChannels = ImmutableList.<Channel<?>>builder().addAll(Workers.getOutputChannels(worker)).build();
			ImmutableList<Worker<?, ?>> succs = ImmutableList.<Worker<?, ?>>builder().addAll(Workers.getSuccessors(worker)).build();
			for (int i = 0; i < outChannels.size(); ++i) {
				//Get the successor, or if the output channel is the actual
				//output of the stream graph, null.
				Worker<?, ?> succ = Iterables.get(succs, i, null);
				//Null is "not in stream graph section" (so this works).
				if (!workers.contains(succ)) {
					Token token = new Token(channelNo++);
					Channel<?> channel = outChannels.get(i);
					IOChannel iochannel = new IOChannel(channel, worker, succ, false);
					ioChannelsBuilder.put(token, iochannel);
				}
			}
		}
		this.ioChannels = ioChannelsBuilder.build();
	}

	/**
	 * Returns an immutable set of the workers in this Interpreter's stream
	 * graph section.
	 * @return an immutable set of the workers this Interpreter will execute
	 */
	public ImmutableSet<Worker<?, ?>> getWorkers() {
		return workers;
	}

	public ImmutableMap<Token, IOChannel> getChannels() {
		return ioChannels;
	}

	/**
	 * Interprets the stream graph section by running a pull schedule on the
	 * "bottom-most" workers in the section (firing predecessors as required if
	 * possible) until no more progress can be made.  Returns true if any
	 * "bottom-most" workers were fired.  Note that returning false does not
	 * mean no workers were fired -- some predecessors might have been fired,
	 * but others prevented the "bottom-most" workers from firing.
	 * @return true iff progress was made
	 */
	public boolean interpret() {
		//Fire each sink once if possible, then repeat until we can't fire any
		//sinks.
		boolean fired, everFired = false;
		do {
			fired = false;
			for (Worker<?, ?> sink : sinks)
				everFired |= fired |= pull(sink);
		} while (fired);
		return everFired;
	}

	/**
	 * Fires upstream filters just enough to allow worker to fire, or returns
	 * false if this is impossible.
	 *
	 * This is an implementation of Figure 3-12 from Bill's thesis.
	 *
	 * @param worker the worker to fire
	 * @return true if the worker fired, false if it didn't
	 */
	private boolean pull(Worker<?, ?> worker) {
		//This stack holds all the unsatisfied workers we've encountered
		//while trying to fire the argument.
		Deque<Worker<?, ?>> stack = new ArrayDeque<>();
		stack.push(worker);
		recurse:
		while (!stack.isEmpty()) {
			Worker<?, ?> current = stack.element();
			assert workers.contains(current) : "Executing outside stream graph section";
			//If we're already trying to fire current, current depends on
			//itself, so throw.  TODO: explain which constraints are bad?
			//We have to pop then push so contains can't just find the top
			//of the stack every time.  (no indexOf(), annoying)
			stack.pop();
			if (stack.contains(current))
				throw new IllegalStreamGraphException("Unsatisfiable message constraints", current);
			stack.push(current);

			//Execute predecessors based on data dependencies.
			int channel = indexOfUnsatisfiedChannel(current);
			if (channel != -1) {
				if (!workers.contains(Iterables.get(Workers.getPredecessors(current), channel, null)))
					//We need data from a worker not in our stream graph section,
					//so we can't do anything.
					return false;
				//Otherwise, recursively fire the worker blocking us.
				stack.push(Workers.getPredecessors(current).get(channel));
				continue recurse;
			}

			List<MessageConstraint> constraints = constraintsForRecipient.get(current);
			if (constraints != null)
				//Execute predecessors based on message dependencies; that is,
				//execute any filter that might send a message to the current
				//worker for delivery just prior to its next firing, to ensure
				//that delivery cannot be missed.
				for (MessageConstraint constraint : constraintsForRecipient.get(current)) {
					Worker<?, ?> sender = constraint.getSender();
					long deliveryTime = constraint.getDeliveryTime(Workers.getExecutions(sender));
					//If deliveryTime == current.getExecutions() + 1, it's for
					//our next execution.  (If it's <= current.getExecutions(),
					//we already missed it!)
					if (deliveryTime <= (Workers.getExecutions(sender) + 1)) {
						//We checked in our constructor that message constraints
						//do not cross the interpreter boundary.  Assert that.
						assert workers.contains(sender);
						stack.push(sender);
						continue recurse;
					}
				}

			Workers.doWork(current);
			afterFire(current);
			stack.pop(); //return from the recursion
		}

		//Stack's empty: we fired the argument.
		return true;
	}

	/**
	 * Searches the given worker's input channels for one that requires more
	 * elements before the worker can fire, returning the index of the found
	 * channel or -1 if the worker can fire.
	 */
	private <I, O> int indexOfUnsatisfiedChannel(Worker<I, O> worker) {
		List<Channel<? extends I>> channels = Workers.getInputChannels(worker);
		List<Rate> peekRates = worker.getPeekRates();
		List<Rate> popRates = worker.getPopRates();
		for (int i = 0; i < channels.size(); ++i) {
			Rate peek = peekRates.get(i), pop = popRates.get(i);
			if (peek.max() == Rate.DYNAMIC || pop.max() == Rate.DYNAMIC)
				throw new UnsupportedOperationException("Unbounded input rates not yet supported");
			int required = Math.max(peek.max(), pop.max());
			if (channels.get(i).size() < required)
				return i;
		}
		return -1;
	}

	/**
	 * Called after the given worker is fired.  Provided for the debug
	 * interpreter to check rate declarations.
	 * @param worker the worker that just fired
	 */
	protected void afterFire(Worker<?, ?> worker) {}

	/**
	 * A Token identifies an input or output of this interpreter instance.
	 */
	public static class Token implements Serializable {
		private static final long serialVersionUID = 1L;
		private final int id;
		private Token(int id) {
			this.id = id;
		}
	}

	/**
	 * An IOChannel contains information about an input or output channel of
	 * this interpreter.
	 */
	public static class IOChannel {
		private final Channel<?> channel;
		private final Worker<?, ?> upstream, downstream;
		private final boolean isInput;
		private IOChannel(Channel<?> channel, Worker<?, ?> upstream, Worker<?, ?> downstream, boolean isInput) {
			this.channel = channel;
			this.upstream = upstream;
			this.downstream = downstream;
			this.isInput = isInput;
		}
		public Channel<?> getChannel() {
			return channel;
		}
		/**
		 * Returns the worker that puts input into this channel, or null if this
		 * channel is the overall input to the stream graph.
		 * @return the worker that puts input into this channel, or null if this
		 * channel is the overall input to the stream graph.
		 */
		public Worker<?, ?> getUpstreamWorker() {
			return upstream;
		}
		/**
		 * Returns the worker that takes output from this channel, or null if
		 * this channel is the overall output of the stream graph.
		 * @return the worker that takes output from this channel, or null if
		 * this channel is the overall output of the stream graph.
		 */
		public Worker<?, ?> getDownstreamWorker() {
			return downstream;
		}
		public boolean isInput() {
			return isInput;
		}
		public boolean isOutput() {
			return !isInput();
		}
	}
}