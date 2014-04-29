package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.inference.Parser.Mode;

public class SerializationTests {

	private static final boolean testLatentDeps = false;
	private static final boolean testJoint = false;

	private Parser trained, readIn;
	private File f;
	
	@Before
	public void setupSerStuff() throws IOException {
		f = File.createTempFile("ParserTests", ".model");
	}
	
	@Test
	public void serializationFrameId() throws IOException {
		trained = new Parser(Mode.FRAME_ID, false, true);
		ParserTests.overfitting(trained, true, "FRAME_ID_SER1");
		trained.writeModel(f);
		readIn = new Parser(f);
		ParserTests.overfitting(readIn, false, "FRAME_ID_SER2");
	}

	@Test
	public void serializationFrameIdWithLatentDeps() throws IOException {
		if(!testLatentDeps) assertTrue("not testing latent deps", false);
		trained = new Parser(Mode.FRAME_ID, true, true);
		ParserTests.overfitting(trained, true, "FRAME_ID_LATENT_SER1");
		trained.writeModel(f);
		readIn = new Parser(f);
		ParserTests.overfitting(readIn, false, "FRAME_ID_LATENT_SER2");
	}
	
	@Test
	public void serializationJoint() throws IOException {
		if(!testJoint) assertTrue("not testing joint", false);
		trained = new Parser(Mode.JOINT_FRAME_ARG, false, true);
		trained.params.argDecoder.setRecallBias(1d);
		ParserTests.overfitting(trained, true, "JOINT_SER1");
		trained.writeModel(f);
		readIn = new Parser(f);
		ParserTests.overfitting(readIn, false, "JOINT_SER2");
	}
	
	@Test
	public void serializationPipeline() throws IOException {
		trained = ParserTests.getFrameIdTrainedOnDummy();
		trained.setMode(Mode.PIPELINE_FRAME_ARG, false);
		trained.params.argDecoder.setRecallBias(1d);
		ParserTests.overfitting(trained, true, "PIPELINE_SER1");
		trained.writeModel(f);
		readIn = new Parser(f);
		ParserTests.overfitting(readIn, false, "PIPELINE_SER2");
	}
	
}
