/**
 * @author Sumanan sumanan@mit.edu
 * @since May 20, 2013
 */
package edu.mit.streamjit.impl.distributed.runtime.api;

public interface RequestProcessor {

	public void processAPPStatus();

	public void processSysInfo();
	
	public void processMaxCores();
}
