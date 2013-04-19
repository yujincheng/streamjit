package edu.mit.streamjit.impl.compiler.insts;

import static com.google.common.base.Preconditions.*;
import edu.mit.streamjit.impl.compiler.Value;

/**
 * Throws an exception.
 *
 * Note that due to incomplete exception handling support, this instruction
 * never has any successors even if it would be caught by an exception handler
 * in the same method.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 4/18/2013
 */
public final class ThrowInst extends TerminatorInst {
	public ThrowInst(Value exception) {
		super(exception.getType().getTypeFactory(), 1);
		setOperand(0, exception);
	}

	@Override
	protected void checkOperand(int i, Value v) {
		checkArgument(v.getType().isSubtypeOf(getType().getTypeFactory().getType(Throwable.class)));
		super.checkOperand(i, v);
	}

	@Override
	public String toString() {
		return String.format("%s: throw %s", getName(), getOperand(0).getName());
	}
}
