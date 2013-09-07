package edu.mit.streamjit.test.regression;

import com.jeffreybosboom.serviceproviderprocessor.ServiceProvider;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Pipeline;
import edu.mit.streamjit.api.Splitjoin;
import edu.mit.streamjit.test.Benchmark;
import edu.mit.streamjit.test.Benchmarker;
import edu.mit.streamjit.test.Datasets;
import java.util.Collections;
import java.util.List;

/**
 * @since 9/6/2013 4:34PM EDT
 */
@ServiceProvider(Benchmark.class)
public class Reg20130906_043404_467 implements Benchmark {
	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public OneToOneElement<Object, Object> instantiate() {
		return new Splitjoin(new edu.mit.streamjit.api.DuplicateSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new Pipeline(new Splitjoin(new edu.mit.streamjit.api.RoundrobinSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new Splitjoin(new edu.mit.streamjit.api.RoundrobinSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new Pipeline(new edu.mit.streamjit.impl.common.TestFilters.Multiplier(2)), new edu.mit.streamjit.impl.common.TestFilters.ArrayListHasher(1)), new Pipeline(new Pipeline(new edu.mit.streamjit.impl.common.TestFilters.Multiplier(100), new edu.mit.streamjit.impl.common.TestFilters.Batcher(2), new edu.mit.streamjit.impl.common.TestFilters.PeekingAdder(10))), new Splitjoin(new edu.mit.streamjit.api.RoundrobinSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new Splitjoin(new edu.mit.streamjit.api.RoundrobinSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new edu.mit.streamjit.impl.common.TestFilters.Batcher(2)))), new Pipeline(new Splitjoin(new edu.mit.streamjit.api.RoundrobinSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new Splitjoin(new edu.mit.streamjit.api.DuplicateSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(3)), new edu.mit.streamjit.impl.common.TestFilters.PeekingAdder(3), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(100), new Pipeline(new edu.mit.streamjit.api.Identity(), new edu.mit.streamjit.impl.common.TestFilters.PeekingAdder(10), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(100)), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(3)), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(3)), new Splitjoin(new edu.mit.streamjit.api.DuplicateSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(2), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(100)), new edu.mit.streamjit.impl.common.TestFilters.Multiplier(3), new Splitjoin(new edu.mit.streamjit.api.DuplicateSplitter(), new edu.mit.streamjit.api.RoundrobinJoiner(), new edu.mit.streamjit.impl.common.TestFilters.ArrayListHasher(1), new edu.mit.streamjit.impl.common.TestFilters.ArrayListHasher(1))));
	}
	@Override
	public List<Dataset> inputs() {
		Dataset ds = Datasets.allIntsInRange(0, 1000);
		return Collections.singletonList(ds.withOutput(Datasets.outputOf(new edu.mit.streamjit.impl.interp.InterpreterStreamCompiler(), instantiate(), ds.input())));
	}
	@Override
	public String toString() {
		return getClass().getSimpleName();
	}
	public static void main(String[] args) {
		Benchmarker.runBenchmark(new Reg20130906_043404_467(), new edu.mit.streamjit.impl.compiler.CompilerStreamCompiler()).get(0).print(System.out);
	}
}
