package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.Configuration;
import edu.jhu.hlt.fnparse.util.DefaultConfiguration;

public class FNFrameInstanceProvider implements FrameInstanceProvider {
	
	@Override
	public String getName() { return "FrameNet_frame_instance"; }
	
	@Override
	public List<FrameInstance> getFrameInstances() {
		List<FrameInstance> allFI = new Vector<FrameInstance>();

		Configuration conf = new DefaultConfiguration();
		List<Frame> allFrames = conf.getFrameIndex().allFrames();
		Map<String, Frame> mapNameToFrame = new HashMap<String, Frame>();
		for (Frame ff : allFrames){
			assert mapNameToFrame.get(ff.getName())==null;
			mapNameToFrame.put(ff.getName(), ff);
		}
		// Make a hashmap of frame ids to frames. for easy processing.
		try {
			File folder = UsefulConstants.fullTextXMLDirPath;
			File[] listOfFiles = folder.listFiles();

			XPath xPath = XPathFactory.newInstance().newXPath();
			for (File file : listOfFiles) {
				if (file.isFile()) {
					//System.out.println(file.getName());
					NodeList sentenceNodes = getNodeList("/fullTextAnnotation/sentence", file);
					for(int i = 0; i < sentenceNodes.getLength(); i++){
						Element sentenceNode = (Element)sentenceNodes.item(i);
						String sentenceId =sentenceNode.getAttribute("corpID") +sentenceNode.getAttribute("docID") +sentenceNode.getAttribute("ID");
				 		String sentenceText = getNodeList("./text", sentenceNode).item(0).getTextContent();
						List<Integer> start = new ArrayList<Integer>();
						List<Integer> end  = new ArrayList<Integer>();
						List<String> tokens = new ArrayList<String>();
						List<String> pos = new ArrayList<String>();
						NodeList postagList = getNodeList("./annotationSet/layer[@name='PENN']/label",sentenceNode);
						for (int l=0; l < postagList.getLength(); l++){
							Element tokenElement = (Element)postagList.item(l);
							int startVal = Integer.parseInt(tokenElement.getAttribute("start"));
							start.add(startVal);
							int endVal = Integer.parseInt(tokenElement.getAttribute("end"));
							end.add(endVal);
							tokens.add(sentenceText.substring(startVal, endVal+1));
							pos.add(tokenElement.getAttribute("name"));
						}

						Sentence sentence = new Sentence(sentenceId, tokens.toArray(new String[tokens.size()]), pos.toArray(new String[pos.size()]));
						NodeList targetOccurenceList = getNodeList("./annotationSet[@frameName]", sentenceNode);

						for(int k = 0; k < targetOccurenceList.getLength(); k++){
							Element targetOccurence = (Element)targetOccurenceList.item(k);
							String luID     = targetOccurence.getAttribute("luID");
							String luName   = targetOccurence.getAttribute("luName");
							String frameID  = targetOccurence.getAttribute("frameID");
							String frameName = targetOccurence.getAttribute("frameName");
							Element tagetTokenElement = (Element) getNodeList("./layer[@name='Target']/label", targetOccurence).item(0);
							String startIdx = tagetTokenElement.getAttribute("start");
							String endIdx = tagetTokenElement.getAttribute("end");
							Integer triggerIdx = start.indexOf(new Integer(Integer.parseInt(startIdx)));
							assert triggerIdx != -1;
							Frame tmpFrame = mapNameToFrame.get(frameName);
							assert tmpFrame != null || frameName.equals("Test35");
							if(tmpFrame != null){
								Span[] tmpSpans = new Span[tmpFrame.numRoles()];
							
								allFI.add(new FrameInstance(tmpFrame, triggerIdx, tmpSpans, sentence));
							}
						}
					}
				}
			}
		}
		catch (Exception e){
			throw new RuntimeException(e);
		}
		return allFI;
	}

	private NodeList getNodeList(String path, File file) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document xmlDocument = db.parse(file);
		return (NodeList) xPath.compile(path).evaluate(xmlDocument, XPathConstants.NODESET);
	}

	private NodeList getNodeList(String path, Document xmlDocument) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(path).evaluate(xmlDocument, XPathConstants.NODESET);
	}

	private NodeList getNodeList(String path, Element e) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(path).evaluate(e, XPathConstants.NODESET);
	}

	private Node getNode(String path, Element e) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (Node) xPath.compile(path).evaluate(e, XPathConstants.NODE);
	}


}
