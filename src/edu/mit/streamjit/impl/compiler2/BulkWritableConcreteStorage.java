package edu.mit.streamjit.impl.compiler2;

import edu.mit.streamjit.impl.blob.Buffer;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 12/5/2013
 */
public interface BulkWritableConcreteStorage extends ConcreteStorage {
	public void bulkWrite(Buffer source, int index, int count);
}