/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package weka.core;

import weka.core.*;
import weka.classifiers.*;
import weka.classifiers.multilabel.*;
import java.util.*;
import java.io.*;

/**
 * WindowIncrementalEvaluator - For Evaluating Incremental (Updateable) Classifiers.
 * @author 		Jesse Read (jesse@tsc.uc3m.es)
 * @version 	October, 2011
 */
public class WindowIncrementalEvaluator {

	public static void runExperiment(MultilabelClassifier h, String args[]) throws Exception {
		h.setOptions(args);
		evaluateModel(h,args);
	}

	static String measures[] = new String[]{"Accuracy", "Exact_match", "H_acc", "Build_time", "Total_time"};

	public static Result[] evaluateModel(MultilabelClassifier h, Instances D) throws Exception {
		return evaluateModel(h,D,20);
	}

	/**
	 * Evaluate a multi-label data-stream model over a moving window.
	 * The window is sampled every N/20 instances, for a total of 20 windows.
	 */
	public static Result[] evaluateModel(MultilabelClassifier h, Instances D, int numWindows) throws Exception {


		if (h.getDebug())
			System.out.println(":- Classifier -: "+h.getClass().getName()+": "+Arrays.toString(h.getOptions()));

		int L = D.classIndex();
		Result results[] = new Result[numWindows-1];		// we don't record the results from the initial window
		results[0] = new Result(L);
		int windowSize = (int)Math.floor(D.numInstances() / (double)numWindows);
		Instances D_init = new Instances(D,0,windowSize); 	// initial window
		h.buildClassifier(D_init); 							// initial classifir
		double t = 0.5;										// initial thtreshold

		long train_time = 0;
		long test_time = 0;

		System.out.print("    #i ");
		for (String m : measures) {
			System.out.print(" , ");
			System.out.print(m);
		}
		System.out.println("");

		int w_num = 0;
		D = new Instances(D,windowSize,D.numInstances()-windowSize);
		for(int i = 0; i < D.numInstances(); i++) {
			Instance x = D.instance(i);
			AbstractInstance x_ = (AbstractInstance)((AbstractInstance) x).copy(); 		// copy 
																						// but don't clear the values, we may need this for ADWIN
			//for(int j = 0; j < L; j++)  
			//	x_.setValue(j,0.0);

			// test & record prediction
			long before_test = System.currentTimeMillis();
			double y[] = h.distributionForInstance(x_);
			long after_test = System.currentTimeMillis();
			test_time += (after_test-before_test);
			results[w_num].addResult(y,x);

			// update 
			long before = System.currentTimeMillis();
			((UpdateableClassifier)h).updateClassifier(x);
			long after = System.currentTimeMillis();
			train_time += (after-before);

			// evaluate every windowSize-th instance
			if (i > 0 && i % windowSize == 0) {
				System.out.print("#"+Utils.doubleToString((double)i,6,0));
				// calculate results
				results[w_num].setInfo("Type","ML");
				results[w_num].setInfo("Threshold",String.valueOf(t));
				results[w_num].output = Result.getStats(results[w_num]);
				//HashMap<String,Double> o = MLEvalUtils.getMLStats(results[w_num].predictions,results[w_num].actuals,String.valueOf(t));
				results[w_num].output.put("Test_time",(test_time)/1000.0);
				results[w_num].output.put("Build_time",(train_time)/1000.0);
				results[w_num].output.put("Total_time",(test_time+train_time)/1000.0);
				// display results (to CLI)
				for (String m : measures) {
					System.out.print(" , ");
					System.out.print(Utils.doubleToString(results[w_num].output.get(m),7,4));
				}
				System.out.println("");

				// set threshold for next window
				t = MLEvalUtils.calibrateThreshold(results[w_num].predictions,results[w_num].output.get("LCard_real"));
				w_num++;
				if (w_num < results.length) {
					results[w_num] = new Result(L);
				}
				else
					break;
			}
		}

		return results;
	}

	/**
	 * Build and evaluate a multi-label model.
	 * With command-line options.
	 */
	public static Result evaluateModel(MultilabelClassifier h, String options[]) throws Exception {

		//Load Instances
		Instances D = null;
		try {
			String filename = Utils.getOption('t', options);
			D = new Instances(new BufferedReader(new FileReader(filename)));
		} catch(IOException e) {
			e.printStackTrace();
			throw new Exception("[Error] Failed to Load Instances from file");
		}

		//Concatenate the Options in the @relation name (in format 'dataset-name: <options>') to the cmd line options
		String doptions[] = null;
		try {
			doptions = MLUtils.getDatasetOptions(D);
		} catch(Exception e) {
			throw new Exception("[Error] Failed to Set Options from @Relation Name");
		}

		//Set Options from the command line, any leftover options will most likely be used in the code that follows
		try {
			int c = Integer.parseInt(Utils.getOption('C',doptions));
			// if negative, then invert ...
			if ( c < 0) {
				c = -c;
				D = MLUtils.switchAttributes(D,c);
			}
			// end
			D.setClassIndex(c);
		} catch(Exception e) {
			System.err.println("[Error] Failed to Set Options from Command Line -- Check\n\t The spelling of the SL classifier\n\t That an option isn't on the wrong side of the '--'");
			System.exit(1);
		}

		//Check for the essential -C option. If still nothing set, we can't continue
		if(D.classIndex() < 0) 
			throw new Exception("You must supply the number of labels either in the @Relation Name or on the command line: -C <num> !");

		options = Utils.splitOptions(Utils.joinOptions(options) + "," + Utils.joinOptions(doptions)+", ");

		if (h.getDebug()) System.out.println(":- Dataset -: "+MLUtils.getDatasetName(D)+"\tL="+D.classIndex()+"");


		Result results[] = evaluateModel(h,D,20);
		/*
		for (Result result: results) {
			System.out.println(""+result.output.get("Accuracy"));
		}
		*/
		return results[results.length-1];
	}

	public static void printOptions(Enumeration e) {

		// Evaluation Options
		StringBuffer text = new StringBuffer();
		text.append("\n\nEvaluation Options:\n\n");
		text.append("-t\n");
		text.append("\tSpecify the dataset (required)\n");
		// Multilabel Options
		text.append("\n\nMultilabel Options:\n\n");
		while (e.hasMoreElements()) {
			Option o = (Option) (e.nextElement());
			text.append("-"+o.name()+'\n');
			text.append(""+o.description()+'\n');
		}

		System.out.println(""+text);
	}

	public static void evaluation(MultilabelClassifier h, String args[]) {
		try {
			 WindowIncrementalEvaluator.runExperiment(h,args);
		} catch(Exception e) {
			System.err.println("Evaluation exception ("+e+"); failed to run experiment");
			e.printStackTrace();
			WindowIncrementalEvaluator.printOptions(h.listOptions());
		}
	}
}