package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.Collections;

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
			//We are assuming that every file in fullTextXMLDirPath is a data file
			XPath xPath = XPathFactory.newInstance().newXPath();
			for (File file : listOfFiles) {
				if (file.isFile()) {
					//sentenceNodes is a list of sentences
					NodeList sentenceNodes = getNodeList("/fullTextAnnotation/sentence", file);
					for(int i = 0; i < sentenceNodes.getLength(); i++){
						Element sentenceNode = (Element)sentenceNodes.item(i);
						// One sentenceNode contains the sentenceText, 
						// the tokenization information, (character indices of start and end of tokens)
						// And one annotationSet per target
						String sentenceId =sentenceNode.getAttribute("corpID") +sentenceNode.getAttribute("docID") +sentenceNode.getAttribute("ID");
						String sentenceText = getNodeList("./text", sentenceNode).item(0).getTextContent();
						List<Integer> start = new ArrayList<Integer>();
						List<Integer> end  = new ArrayList<Integer>();
						List<String> tokens = new ArrayList<String>();
						List<String> pos = new ArrayList<String>();
						NodeList postagList = getNodeList("./annotationSet/layer[@name='PENN']/label",sentenceNode);
						
						// Extract the pos tags and character level, token start and end info
						for (int l=0; l < postagList.getLength(); l++){
							Element tokenElement = (Element)postagList.item(l);
							int startVal = Integer.parseInt(tokenElement.getAttribute("start"));
							start.add(startVal);
							int endVal = Integer.parseInt(tokenElement.getAttribute("end"));
							end.add(endVal);
							tokens.add(sentenceText.substring(startVal, endVal+1));
							pos.add(tokenElement.getAttribute("name"));
						}
						
						// Sort the character level start and end, 
						// these would be used later while finding the token spans of arguments
						Collections.sort(start);
						Collections.sort(end);
						
						// Create Sentence object to be used while creating FrameInstance
						Sentence sentence = new Sentence(getName(), sentenceId, tokens.toArray(new String[tokens.size()]), pos.toArray(new String[pos.size()]));
						
						// Now loop over every annotationSet that mentions Frame Information
						NodeList targetOccurenceList = getNodeList("./annotationSet[@frameName]", sentenceNode);
						for(int k = 0; k < targetOccurenceList.getLength(); k++){
							Element targetOccurence = (Element)targetOccurenceList.item(k);
							String luID     = targetOccurence.getAttribute("luID");
							String luName   = targetOccurence.getAttribute("luName");
							String frameID  = targetOccurence.getAttribute("frameID");
							String frameName = targetOccurence.getAttribute("frameName");
							Element targetTokenElement = (Element) getNodeList("./layer[@name='Target']/label", targetOccurence).item(0);
							String startIdx = targetTokenElement.getAttribute("start");
							String endIdx = targetTokenElement.getAttribute("end");
							Integer triggerIdx = start.indexOf(new Integer(Integer.parseInt(startIdx)));
							assert triggerIdx != -1;
							Frame tmpFrame = mapNameToFrame.get(frameName);
							assert tmpFrame != null || frameName.equals("Test35");
							
							// So far we have found the Frame of the annotation set and the trigger token
							// Now we must find the argument spans.
							// The method is to use the character level argument spans found in the FE layer
							// and to find the corresponding token indices.
							if(tmpFrame != null){
								Span[] spansArray = new Span[tmpFrame.numRoles()];
								NodeList spanNodeList = getNodeList("./layer[@name='FE']/label", targetOccurence);
								HashMap<String, Span> feNameToSpan = new HashMap<String, Span>();
								for (int m = 0; m < spanNodeList.getLength(); m++){
									Element spanFEElement = (Element)spanNodeList.item(m);
									try{
										List<String> nullInstantiation = Arrays.asList(new String [] {"INC", "DNI", "INI", "CNI"});
										// Null Instantiations have no span, so we dont mark those
										// They should be handled differently from absent FE/roles but right now they are 
										// treated the same.
										if(! nullInstantiation.contains(spanFEElement.getAttribute("itype"))){
											int si = start.indexOf(Integer.parseInt(spanFEElement.getAttribute("start")));
											assert si > -1;
											int eichar = Integer.parseInt(spanFEElement.getAttribute("end"));
											int ei = Collections.binarySearch(end, eichar);
											// Handle the case that some of the span endings don't coincide with token endings.
											if( ei < 0){
												ei = -ei;
											}
											else{
												ei++;
											}
											assert ei > 0;
											feNameToSpan.put(spanFEElement.getAttribute("name"),new Span(si, ei));
										}

									}
									catch (Exception e){
										throw new RuntimeException(e);
									}
								}
								for(int spanIdx = 0; spanIdx < tmpFrame.numRoles(); spanIdx++){
									String feName = tmpFrame.getRole(spanIdx);
									spansArray[spanIdx] = feNameToSpan.get(feName);
								}
								allFI.add(FrameInstance.newFrameInstance(tmpFrame, triggerIdx, spansArray, sentence));
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
