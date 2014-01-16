package edu.jhu.hlt.fnparse.data;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

import java.io.*;

import org.w3c.dom.*;

import javax.xml.parsers.*;

public class DataUtil {

	public static Map<Sentence, List<FrameInstance>> groupBySentence(List<FrameInstance> fis) {
		throw new RuntimeException("implement me");
		
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


	public static HashMap<String, String[]> lexicalUnitAndRolesOfFrame(String frameName) {
		try {
			HashMap<String, String[]> h = new HashMap<String, String[]>();
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(new File(UsefulConstants.frameXMLDirPath, frameName + ".xml"));

			// TODO : Figure out how to remove this duplication of code. The problem is the types are different
			NodeList lexicalUnitNodes = doc.getElementsByTagName("lexUnit");
			String[] lexicalUnit = new String[lexicalUnitNodes.getLength()];
			for (int i =0; i < lexicalUnitNodes.getLength(); i++){
				Element e = (Element)lexicalUnitNodes.item(i);
				lexicalUnit[i] = e.getAttribute("name");
			}
			h.put("lexicalUnit", lexicalUnit);

			NodeList roleNodes = doc.getElementsByTagName("FE");
			String[] role = new String[roleNodes.getLength()];
			for (int i =0; i < roleNodes.getLength(); i++){
				Element e = (Element)roleNodes.item(i);
				role[i] = e.getAttribute("name");
			}
			h.put("role", role);
			return h;
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
}
