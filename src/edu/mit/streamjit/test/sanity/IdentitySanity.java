package edu.mit.streamjit.test.sanity;

import com.jeffreybosboom.serviceproviderprocessor.ServiceProvider;
import edu.mit.streamjit.api.Identity;
import edu.mit.streamjit.test.AbstractBenchmark;
import edu.mit.streamjit.test.Benchmark;
import edu.mit.streamjit.test.Datasets;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 8/18/2013
 */
@ServiceProvider(Benchmark.class)
public class IdentitySanity extends AbstractBenchmark {
	public IdentitySanity() {
		super("IdentitySanity", Identity.class,
				id(Datasets.allIntsInRange(0, 1_000_000)),
				id(Datasets.nCopies(100, "STRING"))
				);
	}

	private static Dataset id(Dataset dataset) {
		return Dataset.builder(dataset).output(dataset.input()).build();
	}
}
