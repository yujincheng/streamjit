package edu.mit.streamjit.impl.compiler2;

import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.common.BlobHostStreamCompiler;
import edu.mit.streamjit.impl.common.Configuration;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 11/12/2013 (from CompilerStreamCompiler since 8/13/2013)
 */
public final class Compiler2StreamCompiler extends BlobHostStreamCompiler {
	private int maxNumCores = 1;
	private int multiplier = 1;
	private Path dumpFile;
	private boolean timings = false;
	public Compiler2StreamCompiler() {
		super(new Compiler2BlobFactory());
	}

	public Compiler2StreamCompiler maxNumCores(int maxNumCores) {
		this.maxNumCores = maxNumCores;
		return this;
	}

	public Compiler2StreamCompiler multiplier(int multiplier) {
		this.multiplier = multiplier;
		return this;
	}

	public Compiler2StreamCompiler dumpFile(Path path) {
		this.dumpFile = path;
		return this;
	}

	public Compiler2StreamCompiler timings() {
		this.timings = true;
		return this;
	}

	@Override
	protected final int getMaxNumCores() {
		return maxNumCores;
	}

	@Override
	protected final Configuration getConfiguration(Set<Worker<?, ?>> workers) {
		Configuration defaultConfiguration = super.getConfiguration(workers);
		Configuration.Builder builder = Configuration.builder(defaultConfiguration);
		Configuration.IntParameter multiplierParam = (Configuration.IntParameter)builder.removeParameter("multiplier");
		builder.addParameter(new Configuration.IntParameter("multiplier", multiplierParam.getRange(), this.multiplier));

		//For testing, try full data-parallelization across all cores.
		int perCore = multiplier/maxNumCores;
		for (Map.Entry<String, Configuration.Parameter> e : defaultConfiguration.getParametersMap().entrySet())
			if (e.getKey().matches("node(\\d+)core(\\d+)iter")) {
				Configuration.IntParameter p = (Configuration.IntParameter)builder.removeParameter(e.getKey());
				builder.addParameter(new Configuration.IntParameter(e.getKey(), p.getRange(), perCore));
			}

		if (dumpFile != null)
			builder.putExtraData("dumpFile", dumpFile);
		builder.putExtraData("timings", timings);
		return builder.build();
	}

	@Override
	public String toString() {
		return String.format("Compiler2StreamCompiler (%d cores %d mult)", maxNumCores, multiplier);
	}
}
