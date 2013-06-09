/**
 * @author Sumanan sumanan@mit.edu
 * @since May 20, 2013
 */
package edu.mit.streamjit.impl.distributed.runtime.api;

public enum Request implements MessageElement {
	APPStatus {
		@Override
		public void process(RequestProcessor reqProcessor) {
			reqProcessor.processAPPStatus();
		}
	},
	SysInfo {
		@Override
		public void process(RequestProcessor reqProcessor) {
			reqProcessor.processSysInfo();
		}
	},
	maxCores {
		@Override
		public void process(RequestProcessor reqProcessor) {
			reqProcessor.processMaxCores();
		}
	},
	machineID {
		@Override
		public void process(RequestProcessor reqProcessor) {
			reqProcessor.processMachineID();
		}
	}, NodeInfo {
		@Override
		public void process(RequestProcessor reqProcessor) {
			reqProcessor.processNodeInfo();
		}
	};

	@Override
	public void accept(MessageVisitor visitor) {
		visitor.visit(this);
	}

	public abstract void process(RequestProcessor reqProcessor);

}