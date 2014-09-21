package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;

public class DataUtil {

	public static List<Sentence> stripAnnotations(List<? extends FNTagging> tagged) {
		List<Sentence> raw = new ArrayList<Sentence>();
		for(FNTagging t : tagged)
			raw.add(t.getSentence());
		return raw;
	}

	public static Set<Span> getAllArgSpans(FrameInstance fi) {
		Set<Span> s = new HashSet<>();
		for (int k = 0; k < fi.getFrame().numRoles(); k++)
			s.add(fi.getArgument(k));
		s.remove(Span.nullSpan);
		return s;
	}

	public static Map<Span, FrameInstance> getFrameInstanceByTarget(FNTagging t) {
		Map<Span, FrameInstance> m = new HashMap<>();
		for (FrameInstance fi : t.getFrameInstances()) {
			FrameInstance old = m.put(fi.getTarget(), fi);
			if (old != null) {
				throw new RuntimeException(String.format(
						"%s has two FrameInstances at %s:\n%s",
						t.getId(),
						fi.getTarget(),
						Describe.fnTagging(t)));
			}
		}
		return m;
	}

	public static FrameInstance[] getFrameInstancesIndexByHeadword(List<FrameInstance> fis, Sentence s, HeadFinder hf) {
		int n = s.size();
		FrameInstance[] fiByTarget = new FrameInstance[n];
		for(FrameInstance fi : fis) {
			int targetHead = hf.head(fi.getTarget(), s);
			//assert fiByTarget[targetHead] == null;
			if(fiByTarget[targetHead] != null) {
				System.err.println("[DataUtil getFrameInstancesIndexByHeadword] frame instance in " +
						fi.getSentence().getId() + " has more than one frame-trigger per headword @ " + targetHead);
				// keep the FI with more non-null arguments
				if(fi.numRealizedArguments() < fiByTarget[targetHead].numRealizedArguments())
					continue;
			}
			fiByTarget[targetHead] = fi;
		}
		return fiByTarget;
	}

	/**
	 * FNTaggings don't have arguments, this converts them to FNParses with all the
	 * arguments set to nullSpan.
	 */
	public static List<FNParse> convertTaggingsToParses(List<FNTagging> tags) {
		List<FNParse> parses = new ArrayList<>();
		for(FNTagging t : tags)
			parses.add(convertTaggingToParse(t));
		return parses;
	}

	/** Makes a parse with no arguments */
	public static FNParse convertTaggingToParse(FNTagging t) {
		List<FrameInstance> fis = new ArrayList<>();
		for(FrameInstance fi : t.getFrameInstances()) {
			int K = fi.getFrame().numRoles();
			Span[] args = new Span[K];
			Arrays.fill(args, Span.nullSpan);
			fis.add(FrameInstance.newFrameInstance(fi.getFrame(), fi.getTarget(), args, fi.getSentence()));
		}
		return new FNParse(t.getSentence(), fis);
	}

	/**
	 * Drops all arguments from FrameInstances
	 */
	public static List<FNTagging> convertParsesToTaggings(List<FNParse> parses) {
		List<FNTagging> out = new ArrayList<>();
		for(FNParse p : parses)
			out.add(convertParseToTagging(p));
		return out;
	}

	public static FNTagging convertParseToTagging(FNParse p) {
		Sentence s = p.getSentence();
		List<FrameInstance> targets = new ArrayList<>();
		for(FrameInstance fi : p.getFrameInstances())
			targets.add(FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), s));
		return new FNTagging(s, targets);
	}

	/**
	 * takes parses with regular spans as arguments and converts them to arguments with
	 * with-1 (head) spans.
	 */
	public static List<FNParse> convertArgumenSpansToHeads(List<FNParse> fullParses, HeadFinder hf) {
		List<FNParse> out = new ArrayList<>();
		for(FNParse p : fullParses) {
			Sentence sent = p.getSentence();
			List<FrameInstance> oldFis = p.getFrameInstances();
			List<FrameInstance> newFis = new ArrayList<>(oldFis.size());
			for(FrameInstance fi : oldFis) {
				FrameInstance fic = fi.clone();
				int K = fic.numArguments();
				assert K == fic.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span a = fic.getArgument(k);
					if(a.width() > 1) {
						int h = hf.head(a, sent);
						fic.setArgument(k, Span.widthOne(h));
					}
				}
				newFis.add(fic);
			}
			out.add(new FNParse(p.getSentence(), newFis));
		}
		return out;
	}

	/**
	 * In the FN data, there are some parses which have two different FrameInstances
	 * with the same target. Every instance of this I've seen has just been a mistake
	 * (the same Frame, just double tagged). My code is really pedantic and throws an
	 * exception if I produce a parse that has two FrameInstances with the same target,
	 * and this will happen if I use gold frameId through no fault of my own code.
	 * This method selects a FNTagging that doesn't violate this constraint.
	 */
	public static FNTagging filterOutTargetCollisions(FNTagging input) {
		Map<Span, FrameInstance> keep = new HashMap<Span, FrameInstance>();
		boolean someViolation = false;
		for(FrameInstance fi : input.getFrameInstances()) {
			FrameInstance collision = keep.put(fi.getTarget(), fi);
			if(collision != null) {
				someViolation = true;
				// choose the FI with more realized arguments
				if(collision.numRealizedArguments() > fi.numRealizedArguments())
					keep.put(fi.getTarget(), fi);
			}
		}
		if(!someViolation)
			return input;
		else {
			List<FrameInstance> fis = new ArrayList<FrameInstance>(keep.values());
			return new FNTagging(input.getSentence(), fis);
		}
	}
	

	public static Map<Sentence, List<FrameInstance>> groupBySentence(List<FrameInstance> fis) {
		Map<Sentence, List<FrameInstance>> m = new HashMap<Sentence, List<FrameInstance>>();
		for(FrameInstance fi : fis) {
			List<FrameInstance> fiList = m.get(fi.getSentence());
			if(fiList == null) fiList = new ArrayList<FrameInstance>();
			fiList.add(fi);
			m.put(fi.getSentence(), fiList);
		}
		return m;
	}
	
	
	public static <T> List<T> reservoirSample(List<T> all, int howMany, Random rand) {
		List<T> reservoir = new ArrayList<T>();
		int i = 0;
		for(T t : all) {
			if(i < howMany)
				reservoir.add(t);
			else {
				int k = rand.nextInt(i+1);
				if(k < howMany)
					reservoir.set(k, t);
			}
			i++;
		}
		return reservoir;
	}

	public static String[] parseFrameIndexXML(File f, int numFrames) {
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(f);
			String name[] = new String[numFrames];

			// Parse frameIndex.xml(in fileName) to populate the id and name arrays
			NodeList list = doc.getElementsByTagName("frame");
			assert numFrames == list.getLength();
			for(int i=0; i < numFrames; i++){
				Element element = (Element)list.item(i);
				name[i] = element.getAttribute("name");
			}
			return name;
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static <T> List<T> iter2list(Iterator<T> iter) {
		List<T> list = new ArrayList<T>();
		while(iter.hasNext()) list.add(iter.next());
		return list;
	}


	public static <T extends FNTagging> List<T> filterBySentenceLength(
			List<T> all, int maxLength) {
		List<T> list = new ArrayList<>();
		for(T t : all)
			if(t.getSentence().size() <= maxLength)
				list.add(t);
		return list;
	}
}
