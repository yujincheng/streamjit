package edu.mit.streamjit.impl.distributed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Joiner;
import edu.mit.streamjit.api.Splitter;
import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.BlobFactory;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.common.AbstractDrainer.BlobGraph;
import edu.mit.streamjit.impl.common.Configuration.IntParameter;
import edu.mit.streamjit.impl.common.Configuration.PartitionParameter;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.distributed.common.GlobalConstants;
import edu.mit.streamjit.impl.distributed.common.Utils;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;
import edu.mit.streamjit.impl.distributed.runtimer.OnlineTuner;
import edu.mit.streamjit.impl.interp.Interpreter;
import edu.mit.streamjit.partitioner.AbstractPartitioner;

/**
 * This class contains all information about the current streamJit application
 * including {@link BlobGraph}, current {@link Configuration},
 * partitionsMachineMap1, and etc. Three main classes,
 * {@link DistributedStreamCompiler}, {@link Controller} and {@link OnlineTuner}
 * will be using this class of their functional purpose.
 * <p>
 * All member variables of this class are public, because this class is supposed
 * to be used by only trusted classes.
 * </p>
 * 
 * @author Sumanan sumanan@mit.edu
 * @since Oct 8, 2013
 */
public class StreamJitApp {

	/**
	 * Since this is final, lets make public
	 */
	public final String topLevelClass;

	public final Worker<?, ?> source;

	public final Worker<?, ?> sink;

	public final String jarFilePath;

	public final String name;

	public BlobGraph blobGraph;

	public Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap;

	public ImmutableMap<Token, Buffer> bufferMap;

	public List<MessageConstraint> constraints;

	public DrainData drainData = null;

	/**
	 * Keeps track of assigned machine Ids of each blob. This information is
	 * need for draining. TODO: If possible use a better solution.
	 */
	public Map<Token, Integer> blobtoMachineMap;

	/**
	 * blobConfiguration contains decision variables that are tuned by
	 * opentuner. Specifically, a {@link Configuration} that is generated by a
	 * {@link BlobFactory#getDefaultConfiguration(java.util.Set)}.
	 */
	public Configuration blobConfiguration = null;

	public StreamJitApp(String name, String topLevelClass, Worker<?, ?> source,
			Worker<?, ?> sink) {
		this.name = name;
		this.topLevelClass = topLevelClass;
		this.source = source;
		this.sink = sink;
		this.jarFilePath = this.getClass().getProtectionDomain()
				.getCodeSource().getLocation().getPath();

	}

	/**
	 * Builds partitionsMachineMap and {@link BlobGraph} from the
	 * {@link Configuration}, and verifies for any cycles among blobs. If it is
	 * a valid configuration, (i.e., no cycles among the blobs), then this
	 * object's member variables {@link StreamJitApp#blobConfiguration},
	 * {@link StreamJitApp#blobGraph} and
	 * {@link StreamJitApp#partitionsMachineMap} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param config
	 *            New configuration form Opentuer.
	 * @return true iff no cycles among blobs
	 */
	public boolean newConfiguration(Configuration config) {

		Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap = getMachineWorkerMap(
				config, this.source);
		try {
			varifyConfiguration(partitionsMachineMap);
		} catch (StreamCompilationFailedException ex) {
			return false;
		}
		this.blobConfiguration = config;
		return true;
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph} and
	 * {@link StreamJitApp#partitionsMachineMap} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @return true iff no cycles among blobs
	 */
	public boolean newPartitionMap(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		try {
			varifyConfiguration(partitionsMachineMap);
		} catch (StreamCompilationFailedException ex) {
			return false;
		}
		return true;
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph} and
	 * {@link StreamJitApp#partitionsMachineMap} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @throws StreamCompilationFailedException
	 *             if any cycles found among blobs.
	 */
	private void varifyConfiguration(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		List<Set<Worker<?, ?>>> partitionList = new ArrayList<>();
		for (List<Set<Worker<?, ?>>> lst : partitionsMachineMap.values()) {
			partitionList.addAll(lst);
		}

		BlobGraph bg = null;
		try {
			bg = new BlobGraph(partitionList);
		} catch (StreamCompilationFailedException ex) {
			System.err.print("Cycles found in the worker->blob assignment");
			// for (int machine : partitionsMachineMap.keySet()) {
			// System.err.print("\nMachine - " + machine);
			// for (Set<Worker<?, ?>> blobworkers : partitionsMachineMap
			// .get(machine)) {
			// System.err.print("\n\tBlob worker set : ");
			// for (Worker<?, ?> w : blobworkers) {
			// System.err.print(Workers.getIdentifier(w) + " ");
			// }
			// }
			// }
			System.err.println();
			throw ex;
		}
		this.blobGraph = bg;
		this.partitionsMachineMap = partitionsMachineMap;
	}

	/**
	 * Reads the configuration and returns a map of nodeID to list of set of
	 * workers (list of blob workers) which are assigned to the node. Value of
	 * the returned map is list of worker set where each worker set is an
	 * individual blob.
	 * 
	 * @param config
	 * @param workerset
	 * @return map of nodeID to list of set of workers which are assigned to the
	 *         node.
	 */
	private Map<Integer, List<Set<Worker<?, ?>>>> getMachineWorkerMap(
			Configuration config, Worker<?, ?> source) {

		ImmutableSet<Worker<?, ?>> workerset = Workers
				.getAllWorkersInGraph(source);

		Map<Integer, Set<Worker<?, ?>>> partition = new HashMap<>();
		for (Worker<?, ?> w : workerset) {
			IntParameter w2m = config.getParameter(String.format(
					"worker%dtomachine", Workers.getIdentifier(w)),
					IntParameter.class);
			int machine = w2m.getValue();

			if (!partition.containsKey(machine)) {
				Set<Worker<?, ?>> set = new HashSet<>();
				partition.put(machine, set);
			}
			partition.get(machine).add(w);
		}

		Map<Integer, List<Set<Worker<?, ?>>>> machineWorkerMap = new HashMap<>();
		for (int machine : partition.keySet()) {
			List<Set<Worker<?, ?>>> cycleMinimizedBlobs = new ArrayList<>();
			List<Set<Worker<?, ?>>> machineBlobs = getConnectedComponents(partition
					.get(machine));
			{
				for (Set<Worker<?, ?>> blobWorkers : machineBlobs) {
					cycleMinimizedBlobs.addAll(minimizeCycles(blobWorkers));
				}
			}
			machineWorkerMap.put(machine, cycleMinimizedBlobs);
		}
		return machineWorkerMap;
	}

	/**
	 * Goes through all workers assigned to a machine, find the workers which
	 * are interconnected and group them as a blob workers. i.e., Group the
	 * workers which are connected.
	 * <p>
	 * TODO: If any dynamic edges exists then should create interpreter blob.
	 * 
	 * @param workerset
	 * @return list of workers set which contains interconnected workers. Each
	 *         worker set in the list is supposed to run in an individual blob.
	 */
	private List<Set<Worker<?, ?>>> getConnectedComponents(
			Set<Worker<?, ?>> workerset) {
		List<Set<Worker<?, ?>>> ret = new ArrayList<Set<Worker<?, ?>>>();
		while (!workerset.isEmpty()) {
			Deque<Worker<?, ?>> queue = new ArrayDeque<>();
			Set<Worker<?, ?>> blobworkers = new HashSet<>();
			Worker<?, ?> w = workerset.iterator().next();
			blobworkers.add(w);
			workerset.remove(w);
			queue.offer(w);
			while (!queue.isEmpty()) {
				Worker<?, ?> wrkr = queue.poll();
				for (Worker<?, ?> succ : Workers.getSuccessors(wrkr)) {
					if (workerset.contains(succ)) {
						blobworkers.add(succ);
						workerset.remove(succ);
						queue.offer(succ);
					}
				}

				for (Worker<?, ?> pred : Workers.getPredecessors(wrkr)) {
					if (workerset.contains(pred)) {
						blobworkers.add(pred);
						workerset.remove(pred);
						queue.offer(pred);
					}
				}
			}
			ret.add(blobworkers);
		}
		return ret;
	}

	private List<Set<Worker<?, ?>>> minimizeCycles(Set<Worker<?, ?>> blobworkers) {
		Map<Splitter<?, ?>, Joiner<?, ?>> rfctrSplitJoin = new HashMap<>();
		Set<Splitter<?, ?>> splitterSet = getSplitters(blobworkers);
		for (Splitter<?, ?> s : splitterSet) {
			Joiner<?, ?> j = getJoiner(s);
			if (blobworkers.contains(j)) {
				Set<Worker<?, ?>> childWorkers = new HashSet<>();
				getAllChildWorkers(s, childWorkers);
				if (!blobworkers.containsAll(childWorkers)) {
					rfctrSplitJoin.put(s, j);
				}
			}
		}

		List<Set<Worker<?, ?>>> ret = new ArrayList<>();

		for (Splitter<?, ?> s : rfctrSplitJoin.keySet()) {
			if (blobworkers.contains(s)) {
				ret.add(getSplitterReachables(s, blobworkers, rfctrSplitJoin));
			}
		}
		ret.addAll(getConnectedComponents(blobworkers));
		return ret;
	}

	/**
	 * This function has side effect. Modifies the argument.
	 * 
	 * @param s
	 * @param blobworkers1
	 * @return
	 */
	private Set<Worker<?, ?>> getSplitterReachables(Splitter<?, ?> s,
			Set<Worker<?, ?>> blobworkers1,
			Map<Splitter<?, ?>, Joiner<?, ?>> rfctrSplitJoin) {
		assert blobworkers1.contains(s) : "Splitter s in not in blobworkers";
		Set<Worker<?, ?>> ret = new HashSet<>();
		Set<Worker<?, ?>> exclude = new HashSet<>();
		Deque<Worker<?, ?>> queue = new ArrayDeque<>();
		ret.add(s);
		exclude.add(rfctrSplitJoin.get(s));
		blobworkers1.remove(s);
		queue.offer(s);
		while (!queue.isEmpty()) {
			Worker<?, ?> wrkr = queue.poll();
			for (Worker<?, ?> succ : Workers.getSuccessors(wrkr)) {
				process(succ, blobworkers1, rfctrSplitJoin, exclude, queue, ret);
			}

			for (Worker<?, ?> pred : Workers.getPredecessors(wrkr)) {
				process(pred, blobworkers1, rfctrSplitJoin, exclude, queue, ret);
			}
		}
		return ret;
	}

	/**
	 * Since the code in this method repeated in two places in
	 * getSplitterReachables() method, It is re-factored into a private method
	 * to avoid code duplication.
	 */
	private void process(Worker<?, ?> wrkr, Set<Worker<?, ?>> blobworkers1,
			Map<Splitter<?, ?>, Joiner<?, ?>> rfctrSplitJoin,
			Set<Worker<?, ?>> exclude, Deque<Worker<?, ?>> queue,
			Set<Worker<?, ?>> ret) {
		if (blobworkers1.contains(wrkr) && !exclude.contains(wrkr)) {
			ret.add(wrkr);
			blobworkers1.remove(wrkr);
			queue.offer(wrkr);

			for (Entry<Splitter<?, ?>, Joiner<?, ?>> e : rfctrSplitJoin
					.entrySet()) {
				if (e.getValue().equals(wrkr)) {
					exclude.add(e.getKey());
					break;
				} else if (e.getKey().equals(wrkr)) {
					exclude.add(e.getValue());
					break;
				}
			}
		}
	}

	/**
	 * Copied form {@link AbstractPartitioner} class. But modified to support
	 * nested splitjoiners.</p> Returns all {@link Filter}s in a splitjoin. Does
	 * not include the splitter or the joiner.
	 * 
	 * @param splitter
	 * @return Returns all {@link Filter}s in a splitjoin. Does not include
	 *         splitter or joiner.
	 */

	protected void getAllChildWorkers(Splitter<?, ?> splitter,
			Set<Worker<?, ?>> childWorkers) {
		childWorkers.add(splitter);
		Joiner<?, ?> joiner = getJoiner(splitter);
		Worker<?, ?> cur;
		for (Worker<?, ?> childWorker : Workers.getSuccessors(splitter)) {
			cur = childWorker;
			while (cur != joiner) {
				if (cur instanceof Filter<?, ?>)
					childWorkers.add(cur);
				else if (cur instanceof Splitter<?, ?>) {
					getAllChildWorkers((Splitter<?, ?>) cur, childWorkers);
					cur = getJoiner((Splitter<?, ?>) cur);
				} else
					throw new IllegalStateException(
							"Some thing wrong in the algorithm.");

				assert Workers.getSuccessors(cur).size() == 1 : "Illegal State encounted : cur can only be either a filter or a joner";
				cur = Workers.getSuccessors(cur).get(0);
			}
		}
		childWorkers.add(joiner);
	}

	private Set<Splitter<?, ?>> getSplitters(Set<Worker<?, ?>> blobworkers) {
		Set<Splitter<?, ?>> splitterSet = new HashSet<>();
		for (Worker<?, ?> w : blobworkers) {
			if (w instanceof Splitter<?, ?>) {
				splitterSet.add((Splitter<?, ?>) w);
			}
		}
		return splitterSet;
	}

	/**
	 * Find and returns the corresponding {@link Joiner} for the passed
	 * {@link Splitter}.
	 * 
	 * @param splitter
	 *            : {@link Splitter} that needs it's {@link Joiner}.
	 * @return Corresponding {@link Joiner} of the passed {@link Splitter}.
	 */
	protected Joiner<?, ?> getJoiner(Splitter<?, ?> splitter) {
		Worker<?, ?> cur = Workers.getSuccessors(splitter).get(0);
		int innerSplitjoinCount = 0;
		while (!(cur instanceof Joiner<?, ?>) || innerSplitjoinCount != 0) {
			if (cur instanceof Splitter<?, ?>)
				innerSplitjoinCount++;
			if (cur instanceof Joiner<?, ?>)
				innerSplitjoinCount--;
			assert innerSplitjoinCount >= 0 : "Joiner Count is more than splitter count. Check the algorithm";
			cur = Workers.getSuccessors(cur).get(0);
		}
		assert cur instanceof Joiner<?, ?> : "Error in algorithm. Not returning a Joiner";
		return (Joiner<?, ?>) cur;
	}

	public Configuration getStaticConfiguration() {
		Configuration.Builder builder = Configuration.builder();
		builder.putExtraData(GlobalConstants.JARFILE_PATH, jarFilePath)
				.putExtraData(GlobalConstants.TOPLEVEL_WORKER_NAME,
						topLevelClass);
		return builder.build();
	}

	public Configuration getDynamicConfiguration() {
		Configuration.Builder builder = Configuration.builder();

		Map<Integer, Integer> coresPerMachine = new HashMap<>();
		for (Entry<Integer, List<Set<Worker<?, ?>>>> machine : partitionsMachineMap
				.entrySet()) {
			coresPerMachine.put(machine.getKey(), machine.getValue().size());
		}

		PartitionParameter.Builder partParam = PartitionParameter.builder(
				GlobalConstants.PARTITION, coresPerMachine);

		BlobFactory factory = new Interpreter.InterpreterBlobFactory();
		partParam.addBlobFactory(factory);

		blobtoMachineMap = new HashMap<>();

		for (Integer machineID : partitionsMachineMap.keySet()) {
			List<Set<Worker<?, ?>>> blobList = partitionsMachineMap
					.get(machineID);
			for (Set<Worker<?, ?>> blobWorkers : blobList) {
				// TODO: One core per blob. Need to change this.
				partParam.addBlob(machineID, 1, factory, blobWorkers);

				// TODO: Temp fix to build.
				Token t = Utils.getblobID(blobWorkers);
				blobtoMachineMap.put(t, machineID);
			}
		}

		builder.addParameter(partParam.build());
		if (this.blobConfiguration != null)
			builder.addSubconfiguration("blobConfigs", this.blobConfiguration);
		return builder.build();
	}

	/**
	 * From aggregated drain data, get subset of it which is relevant to a
	 * particular machine. Builds and returns machineID to DrainData map.
	 * 
	 * @return Drain data mapped to machines.
	 */
	public ImmutableMap<Integer, DrainData> getDrainData() {
		ImmutableMap.Builder<Integer, DrainData> builder = ImmutableMap
				.builder();

		if (this.drainData != null) {
			for (Integer machineID : partitionsMachineMap.keySet()) {
				List<Set<Worker<?, ?>>> blobList = partitionsMachineMap
						.get(machineID);
				DrainData dd = drainData.subset(getWorkerIds(blobList));
				builder.put(machineID, dd);
			}
		}
		return builder.build();
	}

	private Set<Integer> getWorkerIds(List<Set<Worker<?, ?>>> blobList) {
		Set<Integer> workerIds = new HashSet<>();
		for (Set<Worker<?, ?>> blobworkers : blobList) {
			for (Worker<?, ?> w : blobworkers) {
				workerIds.add(Workers.getIdentifier(w));
			}
		}
		return workerIds;
	}
}
