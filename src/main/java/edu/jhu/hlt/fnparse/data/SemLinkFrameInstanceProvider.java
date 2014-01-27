package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.ling.TaggedWord;

public class SemLinkFrameInstanceProvider implements FrameInstanceProvider {

	@Override
	public String getName() {return "SemLink_frame_instance";}

	@Override
	public List<Sentence> getFrameInstances() {
		return DataUtil.addFrameInstancesToSentences(getFrameInstancesOld());
	}
	
	public List<FrameInstance> getFrameInstancesOld() {
		List<FrameInstance> allFI = new Vector<FrameInstance>();

		List<Frame> allFrames = FrameIndex.getInstance().allFrames();
		Map<String, Frame> mapNameToFrame = new HashMap<String, Frame>();
		for (Frame ff : allFrames){
			assert mapNameToFrame.get(ff.getName())==null;
			mapNameToFrame.put(ff.getName(), ff);
		}
		try{
			// Split line by space and assign to individual variables.
			File semLinkFIFile = UsefulConstants.semLinkFrameInstanceFile;
			List<String> lines = null;

			try{
				lines = Files.readAllLines(semLinkFIFile.toPath(), Charset.defaultCharset());
				int semLinkIndex = -1;
				for(String line : lines){
					semLinkIndex++;
					String[] row = line.split(" ");
					String parseFileName = row[0]; // I would actually read the mrg file and then just get the output.
					int sentenceIndex = Integer.parseInt(row[1]); // starts with 0
					Tree pennTreePRD = UsefulConstants.getPennTree(parseFileName, sentenceIndex, ".prd");
					Tree pennTreeMRG = UsefulConstants.getPennTree(parseFileName, sentenceIndex, ".mrg");
					int triggerIdx = Integer.parseInt(row[2]); // the index of the token
					//			    	String standard = row[3];  		 
					String verb = row[4]; 	  					 
					//			    	String verbnetClass = row[5]; 				 
					String framenetFrameName = row[6];				 
					//			    	String propbankGrouping = row[7]; 			 
					//			    	String senseinventoryGrouping = row[8];   	 
					//			    	String tamString = row[9]; // Tense/Aspect/Mood
					ArrayList<TaggedWord> taggedWord = pennTreeMRG.taggedYield();
					String [] tokens = new String[taggedWord.size()]; 
					String [] pos = new String[taggedWord.size()];
					for(int i = 0; i < taggedWord.size(); i++){
						tokens[i] = taggedWord.get(i).word();
						pos[i] = taggedWord.get(i).tag();
					}
					boolean hasFrameInstanceLabels = false;
					Sentence sentence = new Sentence(getName(), String.format("SL%d", semLinkIndex), tokens, pos, hasFrameInstanceLabels);
					Frame framenetFrame = mapNameToFrame.get(framenetFrameName);
					if( framenetFrame != null){
						Span[] tmpSpans = new Span[framenetFrame.numRoles()];
						Arrays.fill(tmpSpans, Span.nullSpan);
						// TODO argument spans?
						allFI.add(FrameInstance.newFrameInstance(framenetFrame, Span.widthOne(triggerIdx), tmpSpans, sentence));
					}
					else{
						System.err.println(framenetFrameName);
					}
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			/* 
			 * FOR MY CURRENT WORK I DONT NEED ARGUMENT SPANS HOWEVER
			 * Arguments :
			 * 0:1*3:1-ARG0=Agent;Speaker (just choose the first thing) (this says choose the tokens spanned by the tree at token 0 of height 1, and call that a Speaker
			 * 4:0-rel
			 * 5:2-ARG1=Topic;Message/Topic (this says that more than one possible FrameNet role matches, so just add both as separate instances, or discard and dont add any)
			 * this format is explained in http://verbs.colorado.edu/~mpalmer/projects/ace/EPB-data-format.txt
			 * And for getting arguments I need the spans that are addressed by the addresses.
			 * The arguments need to be parsed by a simple regex. 
			 * 3:2*12:1*13:1-ARG0=Agent;Manufacturer
			 * 14:0-rel
			 * 15:1-ARG1=Product;Product
			 * 3:2*12:1-LINK-SLC
			 * 5:2-ARG1=Topic;Message/Topic should all be handled by the regex. 
			 * number:number(*number:number)*-[^=]*(=[alnumALNUM];[alnumALNUM])?
			 * Then I need to find the words spanned by addresses of the form 3:2 (the tree of height 2 at the word 3)
			 */
		}
		catch (Exception e){
			throw new RuntimeException(e);
		}
		return allFI;
	}



}
