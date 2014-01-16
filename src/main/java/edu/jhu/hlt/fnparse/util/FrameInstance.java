package edu.jhu.hlt.fnparse.util;

import java.util.*;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.util.Sentence;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;

import java.io.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;

/**
 * This class should represent all details of the data
 * needed for inference and evaluation, but as little else as
 * possible so that we can add things (like priors over frames
 * in a document cluster) without this code noticing.
 * 
 * @author travis
 */
public class FrameInstance {

	private Frame frame; 
	private int targetIdx;		// index of the target word
	private Sentence sentence;
	
	/**
	 * indices correspond to frame.getRoles()
	 * null-instantiated arguments should be null in the array
	 */
	private Span[] arguments;

	public FrameInstance(Frame frame, int targetIdx, Span[] arguments, Sentence sent) {
		assert (arguments == null && frame == null) || frame.numRoles() == arguments.length;
		this.frame = frame;

		this.targetIdx = targetIdx; // targetIdx is the index of trigger token in the sentence.
		this.arguments = arguments;
		this.sentence = sent;
	}

	public int getTriggerIdx() { return targetIdx; }

	public String getTriggerWord() { return sentence.getWord(targetIdx); }

	public Sentence getSentence() { return sentence; }

	public Frame getFrame() { return frame; }

	public Span getArgument(int roleIdx) { return arguments[roleIdx]; }

	public void setArgument(int roleIdx, Span extent) {
		arguments[roleIdx] = extent;
	}

	public void printThis(){
		System.out.println("Frame : tokens : pos : trigger ");
		System.out.println(this.frame.getName());
		System.out.println(Arrays.toString(this.sentence.getWord()));
		System.out.println(Arrays.toString(this.sentence.getPos()));
		System.out.println(this.sentence.getWord(triggerIdx));
	}
    public static List<FrameInstance> allFrameInstance(){
		List<FrameInstance> allFI = new Vector<FrameInstance>();
		
		List<Frame> allFrames = Frame.allFrames();
		Map<String, Frame> mapNameToFrame = new HashMap<String, Frame>();
		for (Frame ff : allFrames){
			mapNameToFrame.put(ff.getName(), ff);
		}
		// Make a hashmap of frame ids to frames. for easy processing.
		try{
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
						String sentenceText = getNode("/text",sentenceNode).getNodeValue();

						List<Integer> start = new ArrayList<Integer>();
					    List<Integer> end  = new ArrayList<Integer>();
						List<String> tokens = new ArrayList<String>();
						List<String> pos = new ArrayList<String>();
						NodeList postagList = getNodeList("/annotationSet/layer[@name='PENN']/label",sentenceNode);
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
						NodeList targetOccurenceList = getNodeList("/annotationSet[@frameName]", sentenceNode);
						
						for(int k = 0; k < targetOccurenceList.getLength(); k++){
							Element targetOccurence = (Element)targetOccurenceList.item(k);
							String luID     = targetOccurence.getAttribute("luID");
							String luName   = targetOccurence.getAttribute("luName");
							String frameID  = targetOccurence.getAttribute("frameID");
							String frameName = targetOccurence.getAttribute("frameName");
							Element tagetTokenElement = (Element) getNode("/layer[@name='Target']/label", targetOccurence);
							String startIdx = tagetTokenElement.getAttribute("start");
							String endIdx = tagetTokenElement.getAttribute("end");
							Integer triggerIdx = start.indexOf(new Integer(Integer.parseInt(startIdx)));
							Frame tmpFrame = mapNameToFrame.get(frameName);
							Span[] tmpSpans = new Span[tmpFrame.numRoles()];
							Arrays.fill(tmpSpans, Span.NULL_SPAN);
							allFI.add(new FrameInstance(tmpFrame, triggerIdx, tmpSpans, sentence));
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

    static NodeList getNodeList(String path, File file) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document xmlDocument = db.parse(file);
		return (NodeList) xPath.compile(path).evaluate(xmlDocument, XPathConstants.NODESET);
    }
    
    static NodeList getNodeList(String path, Document xmlDocument) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(path).evaluate(xmlDocument, XPathConstants.NODESET);
    }

	static NodeList getNodeList(String path, Element e) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(path).evaluate(e, XPathConstants.NODESET);
	}
	
	static Node getNode(String path, Element e) throws Exception{
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (Node) xPath.compile(path).evaluate(e, XPathConstants.NODE);
	}
}
