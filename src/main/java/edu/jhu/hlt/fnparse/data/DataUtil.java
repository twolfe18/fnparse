package edu.jhu.hlt.fnparse.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

import java.io.*;

import org.w3c.dom.*;

import javax.xml.parsers.*;

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
		Map<Sentence, List<FrameInstance>> m = DataUtil.groupBySentence(fis);
		List<Sentence> out = new ArrayList<Sentence>();
		for(Map.Entry<Sentence, List<FrameInstance>> x : m.entrySet()) {
			Sentence s = x.getKey();
			for(FrameInstance fi : x.getValue())
				s.addFrameInstance(fi);
			out.add(s);
		}
		return out;
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


	/**
	 * will return a map with two keys:
	 *   "lexicalUnits" has value of type LexicalUnit[]
	 *   "roles" has value of type String[]
	 */
	public static HashMap<String, Object> lexicalUnitAndRolesOfFrame(String frameName) {
		try {
			HashMap<String, Object> h = new HashMap<String, Object>();
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			File f = new File(UsefulConstants.frameXMLDirPath, frameName + ".xml");
			Document doc = db.parse(f);
			//System.out.println("loading LUs and roles from " + f.getPath());

			// TODO : Figure out how to remove this duplication of code. The problem is the types are different
			NodeList lexicalUnitNodes = doc.getElementsByTagName("lexUnit");
			LexicalUnit[] lexicalUnits = new LexicalUnit[lexicalUnitNodes.getLength()];
			for (int i =0; i < lexicalUnitNodes.getLength(); i++){
				Element e = (Element)lexicalUnitNodes.item(i);
				String luStr = e.getAttribute("name");
				String[] luAr = luStr.split("\\.");
				//System.out.println("luStr=\"" + luStr + "\"");
				//System.out.println("luAr=" + Arrays.toString(luAr) + "\n");
				if(luAr.length != 2) throw new RuntimeException();
				lexicalUnits[i] = new LexicalUnit(luAr[0], luAr[1]);
			}
			h.put("lexicalUnits", lexicalUnits);

			NodeList roleNodes = doc.getElementsByTagName("FE");
			String[] role = new String[roleNodes.getLength()];
			for (int i =0; i < roleNodes.getLength(); i++){
				Element e = (Element)roleNodes.item(i);
				role[i] = e.getAttribute("name");
			}
			h.put("roles", role);
			return h;
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
}
