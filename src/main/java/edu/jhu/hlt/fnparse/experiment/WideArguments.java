package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;

/**
 * I'm trying to decide if using just headwords to predict arguments/roles is
 * reasonable, or if I should be doing a flat prediction over (role,span) items.
 * To inform this decision, I want to look at the wider argument spans, were the
 * headword may not well describe the argument and see how many there are.
 * 
 * TODO fix the headfinder, it is garbage
 * 
 * @author travis
 */
public class WideArguments {

	public static void main(String[] args) {
		//showWideArgs();
		measureArgWidth();
	}

	public static class ArgWithWidth {
		public static final int MAX_ARG_WIDTH = 12;
		private Frame frame;
		private String role;
		private int[] argWidths;
		private int totalObservations;

		public ArgWithWidth(Frame f, int role) {
			this(f, f.getRole(role));
		}

		public ArgWithWidth(Frame f, String role) {
			this.frame = f;
			this.role = role;
			this.argWidths = new int[MAX_ARG_WIDTH + 2];
		}

		@Override
		public String toString() {
			return String.format("<ArgWithWidth %s.%s N=%d %.1f%%<5w %.1f%%<10w>",
					frame.getName(), role,
					totalObservations,
					100d * proportionShorterThan(5),
					100d * proportionShorterThan(10));
		}

		public void observeArgument(Span s) {
			totalObservations++;
			int w = s.width();
			if (w < MAX_ARG_WIDTH) {
				argWidths[w]++;
			} else {
				argWidths[argWidths.length - 1]++;
			}
		}

		public double proportionShorterThan(int width) {
			int n = 0, d = 0;
			for (int i = 0; i < argWidths.length; i++) {
				d += argWidths[i];
				if (i < width)
					n += argWidths[i];
			}
			return ((double) n) / d;
		}

		public double widthScore() {
			double s = 0d;
			for (int i = 0; i < argWidths.length; i++)
				s += argWidths[i] * widthScore(i);
			return s;
		}

		public static int widthScore(int width) {
			if (width == 1)
				return -2;
			if (width <= 3)
				return -1;
			if (width <= 5)
				return 0;
			if (width <= 8)
				return 1;
			else
				return 2;
		}
	}

	public static void measureArgWidth() {
		long start = System.currentTimeMillis();
		List<ArgWithWidth> args = new ArrayList<>();
		ArgWithWidth[][] widths =
				new ArgWithWidth[FrameIndex.framesInFrameNet + 1][100];
		for (FNParse p : DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())) {
			for (FrameInstance fi : p.getFrameInstances()) {
				int f = fi.getFrame().getId();
				for (int k = 0; k < fi.numArguments(); k++) {
					Span s = fi.getArgument(k);
					if (s == Span.nullSpan)
						continue;
					ArgWithWidth w = widths[f][k];
					if (w == null) {
						w = new ArgWithWidth(fi.getFrame(), k);
						widths[f][k] = w;
						args.add(w);
					}
					w.observeArgument(s);
				}
			}
		}

		// Sort roles by their width distribution
		Collections.sort(args, new Comparator<ArgWithWidth>() {
			@Override
			public int compare(ArgWithWidth o1, ArgWithWidth o2) {
				double w = o1.widthScore() - o2.widthScore();
				if (w < 0d) return -1;
				if (w > 0d) return 1;
				return 0;
			}
		});

		// Print out the top and bottom of the list
		int k = 20;
		System.out.println("frame-roles with shortes arguments:");
		for (int i = 0; i < k; i++) {
			ArgWithWidth a = args.get(i);
			System.out.printf("% 2d %-25s %.2f\n", i+1, a, a.widthScore());
		}
		System.out.println("frame-roles with longest arguments:");
		for (int i = 0; i < k; i++) {
			int j = (args.size() - i) - 1;
			ArgWithWidth a = args.get(j);
			System.out.printf("% 2d %-25s %.2f\n", j+1, a, a.widthScore());
		}
		System.out.printf("done, took %.1f seconds\n",
				(System.currentTimeMillis() - start) / 1000d);
	}

	public static void showWideArgs() {
		int total = 0, wide = 0;
		HeadFinder hf = new SemaforicHeadFinder();
		Iterator<FNParse> iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
		while(iter.hasNext()) {
			FNParse p = iter.next();
			Sentence sent = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span s = fi.getArgument(k);
					if(s == Span.nullSpan) continue;
					total++;
					if(s.width() < 4) continue;
					wide++;
					int head = hf.head(s, sent);
					System.out.printf("%-15s %d\t%-18s %-12s %s\n", sent.getId(), s.width(), fi.getFrame().getRole(k), sent.getWord(head), sent.wordsIn(s));
				}
			}
		}
		System.out.println("total = " + total + ", wide = " + wide);
	}
}
