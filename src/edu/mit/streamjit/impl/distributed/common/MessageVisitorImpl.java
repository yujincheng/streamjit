package edu.mit.streamjit.impl.distributed.common;

import edu.mit.streamjit.impl.distributed.common.AppStatus.AppStatusProcessor;
import edu.mit.streamjit.impl.distributed.common.Command.CommandProcessor;
import edu.mit.streamjit.impl.distributed.common.ConfigurationString.ConfigurationStringProcessor;
import edu.mit.streamjit.impl.distributed.common.DrainElement.DrainProcessor;
import edu.mit.streamjit.impl.distributed.common.Error.ErrorProcessor;
import edu.mit.streamjit.impl.distributed.common.NodeInfo.NodeInfoProcessor;
import edu.mit.streamjit.impl.distributed.common.Request.RequestProcessor;

/**
 * @author Sumanan sumanan@mit.edu
 * @since May 20, 2013
 */
public class MessageVisitorImpl implements MessageVisitor {

	private AppStatusProcessor asp;
	private CommandProcessor cp;
	private ErrorProcessor ep;
	private RequestProcessor rp;
	private ConfigurationStringProcessor jp;
	private DrainProcessor dp;
	private NodeInfoProcessor np;

	public MessageVisitorImpl(AppStatusProcessor asp, CommandProcessor cp,
			ErrorProcessor ep, RequestProcessor rp,
			ConfigurationStringProcessor jp, DrainProcessor dp,
			NodeInfoProcessor np) {
		this.asp = asp;
		this.cp = cp;
		this.ep = ep;
		this.rp = rp;
		this.jp = jp;
		this.dp = dp;
		this.np = np;
	}

	@Override
	public void visit(AppStatus appStatus) {
		appStatus.process(asp);
	}

	@Override
	public void visit(Command streamJitCommand) {
		streamJitCommand.process(cp);
	}

	@Override
	public void visit(SystemInfo systemInfo) {

	}

	@Override
	public void visit(Error error) {
		error.process(ep);
	}

	@Override
	public void visit(Request request) {
		request.process(rp);
	}

	@Override
	public void visit(ConfigurationString json) {
		json.process(jp);
	}

	@Override
	public void visit(NodeInfo nodeInfo) {
		np.process(nodeInfo);
	}

	@Override
	public void visit(DrainElement drain) {
		drain.process(dp);
	}
}