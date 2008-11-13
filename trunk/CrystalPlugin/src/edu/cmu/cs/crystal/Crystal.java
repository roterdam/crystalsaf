/**
 * Copyright (c) 2006, 2007, 2008 Marwan Abi-Antoun, Jonathan Aldrich, Nels E. Beckman, Kevin
 * Bierhoff, David Dickey, Ciera Jaspan, Thomas LaToza, Gabriel Zenarosa, and others.
 * 
 * This file is part of Crystal.
 * 
 * Crystal is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Crystal is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with Crystal. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package edu.cmu.cs.crystal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;

import edu.cmu.cs.crystal.annotations.AnnotationDatabase;
import edu.cmu.cs.crystal.annotations.AnnotationFinder;
import edu.cmu.cs.crystal.annotations.CrystalAnnotation;
import edu.cmu.cs.crystal.annotations.ICrystalAnnotation;
import edu.cmu.cs.crystal.internal.ICrystalJob;
import edu.cmu.cs.crystal.internal.ISingleCrystalJob;
import edu.cmu.cs.crystal.internal.WorkspaceUtilities;
import edu.cmu.cs.crystal.tac.eclipse.CompilationUnitTACs;
import edu.cmu.cs.crystal.util.Option;
import edu.cmu.cs.crystal.util.Utilities;

/**
 * Provides the ability to run the analyses. Provides output mechanisms for both the Static Analysis
 * developer and the Static Analysis user.
 * 
 * Also maintains several useful data structures. They can be accessed through several "get*"
 * methods.
 * 
 * @author David Dickey
 * @author Jonathan Aldrich
 * @author cchristo
 */
public class Crystal {
	static private class AnnoRegister {
		public String name;
		public boolean isMeta;
	}

	/**
	 * This is the name of the regression-testing logger. The intention of the regression-testing
	 * logger is to produce a output file that can be compared with some reference file to make sure
	 * analysis results are stable.
	 * 
	 * If you want to include regular output into regression tests, you should log it into a
	 * {@link java.util.Logger} object for the name defined here. (Such a logger can be acquired
	 * using <code>java.util.Logger.getLogger(Crystal.REGRESSION_LOGGER)</code>). By default, a
	 * message for each compilation unit being analyzed and for each user problem reported will be
	 * included in the regression-testing log. Note that messages of levels
	 * {@link java.util.Level#WARNING} and {@link java.util.Level#SEVERE} should also be included in
	 * the regression-testing log.
	 * 
	 * @see #reportUserProblem(String, ASTNode, ICrystalAnalysis)
	 * @see #regression
	 */
	public static final String REGRESSION_LOGGER = "edu.cmu.cs.crystal.regression";

	/**
	 * Currently unused default marker type for Crystal.
	 * 
	 * @see IMarker
	 */
	public static final String MARKER_DEFAULT = "edu.cmu.cs.crystal.crystalproblem";

	/**
	 * Currently unused marker attribute for markers of type {@link #MARKER_DEFAULT}.
	 */
	public static final String MARKER_ATTR_ANALYSIS = "analysis";

	private static final Logger logger = Logger.getLogger(Crystal.class.getName());

	private static Logger regressionLogger = Logger.getLogger(REGRESSION_LOGGER);

	// TODO: Make these data structures are immutable (ie unchangable)
	/**
	 * the list of analyses to perfrom
	 */
	private LinkedList<ICrystalAnalysis> analyses;

	/**
	 * The names of the analyses that are enabled.
	 */
	private Set<String> enabledAnalyses;

	/**
	 * Permanent registry for annotation parsers, populated at plugin initialization time.
	 */
	private Map<AnnoRegister, Class<? extends ICrystalAnnotation>> annotationRegistry =
	    new HashMap<AnnoRegister, Class<? extends ICrystalAnnotation>>();

	public Crystal() {
		analyses = new LinkedList<ICrystalAnalysis>();
		enabledAnalyses = new HashSet<String>();
	}

	/**
	 * Registers an analysis with the framework. All analyses must be registered in order for them
	 * to be invoked.
	 * 
	 * @param analysis
	 *            the analysis to be used
	 */
	public void registerAnalysis(ICrystalAnalysis analysis) {
		analyses.add(analysis);
		enabledAnalyses.add(analysis.getName());
	}

	/**
	 * Retrieves the declaring ASTNode of the binding.
	 * 
	 * The first time this method is called, the mapping between bindings and nodes is created. The
	 * creation time will depend on the size of the workspace. Subsequent calls will simply look up
	 * the values from a mapping.
	 * 
	 * @param binding
	 *            the binding from which you want the declaration
	 * @return the declaration node
	 */
	public ASTNode getASTNodeFromBinding(IBinding binding) {
		throw new UnsupportedOperationException("Retrieving AST nodes for bindings not supported");
	}

	public List<ICrystalAnalysis> getAnalyses() {
		return Collections.unmodifiableList(analyses);
	}

	/**
	 * Runs all of the analyses on the compilation units passed in. Will clear the console before
	 * starting. Will clear ALL the markers for each compilation unit before starting. This will run
	 * all the analyses on a single compilation unit at a time. After finishing a compilation unit,
	 * we may not hold onto any ASTNodes
	 * 
	 * @param reanalyzeList
	 *            The compilation units to analyze
	 * @deprecated Use {@link #runAnalyses(List<ICompilationUnit>,IProgressMonitor)} instead
	 */
	@Deprecated
	public void runAnalyses(List<ICompilationUnit> reanalyzeList) {
		runAnalyses(reanalyzeList, null);
	}

	/**
	 * Runs all of the analyses on the compilation units passed in. Will clear the console before
	 * starting. Will clear ALL the markers for each compilation unit before starting. This will run
	 * all the analyses on a single compilation unit at a time. After finishing a compilation unit,
	 * we may not hold onto any ASTNodes
	 * 
	 * @param reanalyzeList
	 *            The compilation units to analyze
	 * @param monitor
	 *            the progress monitor used to report progress and request cancellation, or
	 *            <code>null</code> if none. Monitor must not be initialized with
	 *            {@link IProgressMonitor#beginTask(String, int)}.
	 */
	private void runAnalyses(List<ICompilationUnit> reanalyzeList, IProgressMonitor monitor) {
		if (analyses == null || analyses.isEmpty()) {
			logger.warning("Crystal::runAnalyses() No analyses registered");
			return;
		}
		Utilities.nyi();
	}

	public void runAnalyses(IRunCrystalCommand command, IProgressMonitor monitor) {
		runCrystalJob(createJobFromCommand(command, monitor));
	}

	/**
	 * Run the crystal job. At the moment, this consists of calling the {@code run} method on the
	 * job parameter, but reserves the right to run many jobs in parallel.
	 */
	private void runCrystalJob(ICrystalJob job) {
		job.runJobs();
	}

	/**
	 * Given a command to run some analyses on some compilation units, creates a job to run all of
	 * those analyses. This method does many of the things that runAnalysisOnMultiUnit and
	 * runAnalysisOnSingleUnit used to do, but now those activities are packaged up as
	 * ISingleCrystalJobs and in an ICrystalJob.
	 * 
	 * @throws IllegalArgumentException If any analysis name given doesn't exist!
	 */
	private ICrystalJob createJobFromCommand(final IRunCrystalCommand command,
	    final IProgressMonitor monitor) {
		final int num_jobs = command.compilationUnits().size();
		final List<ISingleCrystalJob> jobs = new ArrayList<ISingleCrystalJob>(num_jobs);

		// Get a list of all the analyses to run
		final List<ICrystalAnalysis> analyses_to_use =
		    new ArrayList<ICrystalAnalysis>(command.analyses().size());
		for (String analysis_name : command.analyses()) {
			Option<ICrystalAnalysis> analysis_ = findAnalysisWithName(analysis_name);
			if (analysis_.isSome()) {
				analyses_to_use.add(analysis_.unwrap());
			}
			else {
				throw new IllegalArgumentException("Analysis with name \"" + analysis_name +
						"\" does not exist!");
			}
		}

		// tell analyses that the analysis is about to begin!
		for (ICrystalAnalysis analysis : analyses_to_use ) {
			analysis.beforeAllCompilationUnits();
		}
		
		// Now, create one job per compilation unit
		for (final ICompilationUnit cu : command.compilationUnits()) {

			jobs.add(new ISingleCrystalJob() {
				public void run(final AnnotationDatabase annoDB) {
					if (cu == null) {
						if (logger.isLoggable(Level.WARNING))
							logger.warning("Skipping null CompilationUnit");
					}
					else {
						if (monitor != null) {
							if(monitor.isCanceled())
								return;
							monitor.subTask(cu.getElementName());
						}
						if (logger.isLoggable(Level.INFO))
							logger.info("Running Crystal on: " + cu.getResource().getLocation().toOSString());
						
						// Run each analysis on the current compilation unit.
						CompilationUnit ast_comp_unit =
						    (CompilationUnit) WorkspaceUtilities.getASTNodeFromCompilationUnit(cu);

						// Here, create one TAC cache per compilation unit.
						final CompilationUnitTACs compUnitTacs = new CompilationUnitTACs();

						// Clear any markers that may be onscreen...
						command.reporter().clearMarkersForCompUnit(cu);

						for (ICrystalAnalysis analysis : analyses_to_use) {
							IAnalysisInput input = new IAnalysisInput() {
								public AnnotationDatabase getAnnoDB() {
									return annoDB;
								}

								public Option<CompilationUnitTACs> getComUnitTACs() {
									return Option.some(compUnitTacs);
								}
							};

							// Run the analysis
							analysis.runAnalysis(command.reporter(), input, cu, ast_comp_unit);
						}
					}
					if (monitor != null) {
						if(monitor.isCanceled())
							return;
						// increment monitor
						monitor.worked(1);
					}
				}
			});
		}

		return createCrystalJobFromSingleJobs(command, 
				monitor, num_jobs, jobs, analyses_to_use);
	}

	/**
	 * Given all of the single jobs, create the one analysis job.
	 * 
	 * This basically packages the jobs into an interface, but it also runs
	 * the annotation finder. We may be getting rid of this pre-emptive
	 * annotation finder run soon.
	 */
	private ICrystalJob createCrystalJobFromSingleJobs(
			final IRunCrystalCommand command, final IProgressMonitor monitor,
			final int num_jobs, final List<ISingleCrystalJob> jobs,
			final List<ICrystalAnalysis> analyses_to_use) {
		
		// Just return an implementation of the ICrystalJob interface
		return new ICrystalJob() {
			public List<ISingleCrystalJob> analysisJobs() {
				return Collections.unmodifiableList(jobs);
			}

			public void runJobs() {
				if (monitor != null) {
					String task;
					if(num_jobs == 1)
						task = "Running Crystal on 1 compilation unit.";
					else
						task = "Running Crystal on " + 
							num_jobs + " total compilation units.";
					monitor.beginTask(task, num_jobs);
				}

				AnnotationDatabase annoDB = new AnnotationDatabase();
				AnnotationFinder finder = new AnnotationFinder(annoDB);

				// register annotation parsers from registry
				for (Map.Entry<AnnoRegister, Class<? extends ICrystalAnnotation>> entry : annotationRegistry
				    .entrySet()) {
					annoDB.register(entry.getKey().name, entry.getValue(), entry.getKey().isMeta);
				}

				// register any special classes for the annotation database
				// TODO remove getAnnotationClasses() from ICrystalAnalysis
				for (ICrystalAnalysis crystalAnalysis : analyses_to_use) {
					Map<String, Class<? extends CrystalAnnotation>> map =
					    crystalAnalysis.getAnnotationClasses();
					if (map == null)
						continue;
					for (Map.Entry<String, Class<? extends CrystalAnnotation>> entry : map
					    .entrySet())
						annoDB.register(entry.getKey(), entry.getValue(), false);
				}

				// run the annotation finder on everything
				if (monitor != null)
					monitor.subTask("Scanning annotations of analyzed compilation units");
				if (logger.isLoggable(Level.FINER))
					logger.finer("Scanning annotations of analyzed compilation units");
				for (ICompilationUnit compUnit : command.compilationUnits()) {
					if (compUnit == null)
						continue;
					ASTNode node = WorkspaceUtilities.getASTNodeFromCompilationUnit(compUnit);
					if (monitor != null && monitor.isCanceled())
						// cancel here in case cancellation can produce null or incomplete ASTs
						return;
					if (!(node instanceof CompilationUnit))
						continue;

					// Dummy analysis input
					IAnalysisInput input = new IAnalysisInput() {
						AnnotationDatabase annoDB = new AnnotationDatabase();

						public AnnotationDatabase getAnnoDB() {
							return annoDB;
						}

						public Option<CompilationUnitTACs> getComUnitTACs() {
							return Option.none();
						}
					};

					// Run annotation finder
					finder.runAnalysis(command.reporter(), input, compUnit, (CompilationUnit) node);
				}

				// Now, run every single job
				for (ISingleCrystalJob job : analysisJobs()) {
					job.run(annoDB);
				}
				// Tell all analyses, we are done.
				for (ICrystalAnalysis analysis : analyses_to_use) {
					analysis.afterAllCompilationUnits();
				}
			}
		};
	}

	/**
	 * @param analysis_name
	 * @return
	 */
	private Option<ICrystalAnalysis> findAnalysisWithName(String analysis_name) {
		for (ICrystalAnalysis analysis : this.getAnalyses()) {
			if (analysis.getName().equals(analysis_name))
				return Option.some(analysis);
		}
		return Option.none();
	}

	/**
	 * Gets the root ASTNode for a compilation unit, with bindings on.
	 * 
	 * @param compUnit
	 * @return the root ASTNode for a compilation unit, with bindings on.
	 * @deprecated Use {@link WorkspaceUtilities#getASTNodeFromCompilationUnit(ICompilationUnit)}
	 *             instead
	 */
	private static ASTNode getASTNodeFromCompilationUnit(ICompilationUnit compUnit) {
		return WorkspaceUtilities.getASTNodeFromCompilationUnit(compUnit);
	}

	public void registerAnnotation(String annotationName,
	    Class<? extends ICrystalAnnotation> annoClass, boolean parseAsMeta) {
		AnnoRegister register = new AnnoRegister();
		register.isMeta = parseAsMeta;
		register.name = annotationName;
		annotationRegistry.put(register, annoClass);
	}

}