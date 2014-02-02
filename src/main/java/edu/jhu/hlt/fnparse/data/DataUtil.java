package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class DataUtil {

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
	
	public static List<Sentence> addFrameInstancesToSentences(List<FrameInstance> fis) {
		long start = System.currentTimeMillis();
		Map<Sentence, List<FrameInstance>> m = DataUtil.groupBySentence(fis);
		List<Sentence> out = new ArrayList<Sentence>();
		for(Map.Entry<Sentence, List<FrameInstance>> x : m.entrySet()) {
			Sentence s = x.getKey();
			for(FrameInstance fi : x.getValue())
				s.addFrameInstance(fi);
			out.add(s);
		}
		System.out.printf("[DataUtil frameInst -> Sentence] added %d frame instances to %d sentences in %.1f seconds.\n",
				fis.size(), out.size(), (System.currentTimeMillis()-start)/1000d);
		return out;
	}
	
	public static <T> List<T> reservoirSample(List<T> all, int howMany) {
		Random rand = new Random(9001);
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
}
