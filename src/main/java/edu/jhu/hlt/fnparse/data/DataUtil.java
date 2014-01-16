package edu.jhu.hlt.fnparse.data;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;
import edu.jhu.hlt.fnparse.data.UsefulConstants;
import java.io.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;

public class DataUtil {

	public static Map<Sentence, List<FrameInstance>> groupBySentence(List<FrameInstance> fis) {
		throw new RuntimeException("implement me");
	}

	public static HashMap parseFrameIndexXML(File f, int numFrames) throws Exception {
		HashMap r = new HashMap();
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(f);
		int id[] = new int[numFrames];
		String name[] = new String[numFrames];

		// Parse frameIndex.xml(in fileName) to populate the id and name arrays
		NodeList list = doc.getElementsByTagName("frame");
		assert numFrames == list.getLength();
		for(int i=0; i < numFrames; i++){
			Element element = (Element)list.item(i);
			id[i]=Integer.parseInt(element.getAttribute("ID"));
			name[i]=element.getAttribute("name");
		}
		r.put("id", id);
		r.put("name", name); 
		return r;
	}


	public static HashMap lexicalUnitAndRolesOfFrame(String frameName) throws Exception {
		HashMap h = new HashMap();
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

	// TODO: Convert to unit test.
	// public static void main(String[] args){
	// 	HashMap tmp = parseFrameIndexXML( "//export//a15//prastog3//framenetparser//repo//toydata//frameIndex.xml", 1019);
	// 	int id[] = (int[]) tmp.get("id");
	// 	String name[] = (String []) tmp.get("name");
	// 	for(int i = 0 ; i < 1019; i++){
	// 	    System.out.println(name[i]);
	// 	};
	// }

}