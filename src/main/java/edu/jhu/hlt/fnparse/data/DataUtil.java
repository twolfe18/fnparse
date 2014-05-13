package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

public class DataUtil {

	public static List<Sentence> stripAnnotations(List<? extends FNTagging> tagged) {
		List<Sentence> raw = new ArrayList<Sentence>();
		for(FNTagging t : tagged)
			raw.add(t.getSentence());
		return raw;
	}
	
	/**
	 * FNTaggings don't have arguments, this converts them to FNParses with all the
	 * arguments set to nullSpan.
	 */
	public static List<FNParse> promoteTaggingsToParses(List<FNTagging> tags) {
		List<FNParse> parses = new ArrayList<>();
		for(FNTagging t : tags) {
			List<FrameInstance> fis = new ArrayList<>();
			for(FrameInstance fi : t.getFrameInstances()) {
				int K = fi.getFrame().numRoles();
				Span[] args = new Span[K];
				Arrays.fill(args, Span.nullSpan);
				fis.add(FrameInstance.newFrameInstance(fi.getFrame(), fi.getTarget(), args, fi.getSentence()));
			}
			parses.add(new FNParse(t.getSentence(), fis));
		}
		return parses;
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
}
