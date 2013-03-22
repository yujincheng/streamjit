package edu.mit.streamjit.impl.interp;

import edu.mit.streamjit.api.Worker;

/**
 * A Channel factory.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 3/21/2013
 */
public interface ChannelFactory {
	/**
	 * Creates a channel that will be used to connect the two given workers. If
	 * an implementation doesn't care what channel is used to connect the two
	 * filters, consider returning EmptyChannel. Implementations need not create
	 * a new channel for each call, though not doing so may produce strange
	 * results. Implementations may return null; the results may vary from not
	 * connecting the two workers with a channel to throwing an exception.
	 * <p/>
	 * TODO: Generic bounds are too strict -- the filters don't have to exactly
	 * agree on type, but merely be compatible. But note that isn't of much
	 * import due to erasure.
	 * @param <E> the type of element in the channel
	 * @param upstream the upstream worker (adds elements to the channel), or
	 * null for the overall stream graph input
	 * @param downstream the downstream worker (removes elements from the
	 * channel), or null for the overall stream graph output
	 * @return a channel to connect the two workers
	 */
	public <E> Channel<E> makeChannel(Worker<?, E> upstream, Worker<E, ?> downstream);
}