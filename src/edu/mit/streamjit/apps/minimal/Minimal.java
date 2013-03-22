/**
 * @author Sumanan sumanan@mit.edu
 * @since Mar 7, 2013
 */

package edu.mit.streamjit.apps.minimal;

import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Pipeline;
import edu.mit.streamjit.api.StatefulFilter;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.impl.interp.DebugStreamCompiler;

public class Minimal {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws InterruptedException {

		MinimalKernel kernel = new MinimalKernel();
		StreamCompiler sc = new DebugStreamCompiler();
		CompiledStream<Integer, Void> stream = sc.compile(kernel);
		Integer output;
		for (int i = 0; i < 10000; ++i) {
			stream.offer(i);
			//while ((output = stream.poll()) != null)
				//System.out.println(output);
		}
		stream.drain();
		stream.awaitDraining();
	}

	private static class IntSource extends StatefulFilter<Integer, Integer> {

		public IntSource(int i, int j, int k) {
			super(i, j, k);
			// TODO Auto-generated constructor stub
		}		

		@Override
		public void work() {
			push(pop());
		}
	}

	private static class IntPrinter extends Filter<Integer, Void> {

		public IntPrinter(int i, int j, int k) {
			super(i, j, k);
			// TODO Auto-generated constructor stub
		}

		@Override
		public void work() {
			System.out.println(pop());

		}
	}

	private static class MinimalKernel extends Pipeline<Integer, Void> {

		public MinimalKernel() {
			super(new IntSource(1, 1, 0), new IntPrinter(1, 0, 0));
		}
	}
}
