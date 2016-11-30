package edu.jhu.hlt.uberts.auto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TNonblockingServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TTransportException;

import edu.jhu.hlt.acute.archivers.tar.TarArchiver;
import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.annotate.AnnotateCommunicationService;
import edu.jhu.hlt.concrete.annotate.AnnotateCommunicationService.Iface;
import edu.jhu.hlt.concrete.serialization.archiver.ArchivableCommunication;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.concrete.services.ConcreteThriftException;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.MergeFactsIntoSituationMentionSets;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.hlt.uberts.srl.ConcreteToRelations;
import edu.jhu.prim.Primitives.MutableInt;

/**
 * For documentation on configuration:
 * @see scripts/uberts/annotate-concrete.sh
 * @see UbertsLearnPipeline#build(ExperimentProperties)
 *
 * @author travis
 */
public class FNParseConcreteService implements AnnotateCommunicationService.Iface {
  
  private UbertsLearnPipeline model;
  private AnnotationMetadata meta;
  private String situationType;
  
  Counts<String> ec = new Counts<>();   // event counts
  
  public boolean verbose = false;
  public boolean echo = false;
  
  public FNParseConcreteService(ExperimentProperties config) {
    meta = new AnnotationMetadata()
        .setKBest(1)
        .setTool(config.getString("toolname", "fnparse"))
        .setTimestamp(System.currentTimeMillis() / 1000);
    situationType = config.getString("situationType", "fnparse/fn");
    try {
      model = UbertsLearnPipeline.build(config);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Communication annotate(Communication comm) throws ConcreteThriftException, TException {
    ec.increment("annotate/communication");
    if (verbose)
      Log.info("annotating " + comm.getId());
    if (echo) {
      if (verbose)
        Log.info("echoing back argument!");
      return comm;
    }
    try {

      @SuppressWarnings("resource")
      ConcreteToRelations c2r = new ConcreteToRelations();
      List<RelDoc> sentences = new ArrayList<>();
      for (Section section : comm.getSectionList()) {
        for (Sentence sentence : section.getSentenceList()) {
          c2r.writeSentenceAsDocument(comm, sentence);
          RelDoc doc = c2r.popDoc();
          sentences.add(doc);
        }
      }
      
      MergeFactsIntoSituationMentionSets.FactsForOneDocument anno =
          new MergeFactsIntoSituationMentionSets.FactsForOneDocument(comm);
      
      final MutableInt idx = new MutableInt(0);

      // This is called for every sentence
      model.postDevTestConsumeObserve = (pipe, p) -> {
        List<Step> traj = p.get2();
//        Log.info(comm.getId() + " traj.size=" + traj.size());
        RelDoc inputFacts = sentences.get(idx.v);
        RelDoc annoFacts = new RelDoc(inputFacts.def);
        for (Step s : traj) {
//          Log.info(comm.getId() + "\t" + s);
          if (s.pred) {
            RelLine rl = s.edge.getRelLine("y", "score=" + s.score.forwards());
            annoFacts.items.add(rl);
          }
        }
        anno.addSentenceFacts(annoFacts);
        idx.v++;
        ec.increment("annotate/sentence");
      };

      if (verbose)
        Log.info("running inference...");
      model.runInference(sentences.iterator(), "test");
      assert idx.v == sentences.size();

      if (verbose)
        Log.info("adding annotations to Communication...");
      anno.addSituationMentionSetToCommunication(meta, situationType);
      
//      // DEBUG: can we serialize this to disk (minimum requirement for it making it over the wire)
//      File of = File.createTempFile("fnparse-concrete-service-test", ".comm");
//      if (verbose)
//        Log.info("saving to " + of.getPath());
//      try (BufferedOutputStream b = new BufferedOutputStream(new FileOutputStream(of))) {
//        comm.write(new TCompactProtocol(new TIOStreamTransport(b)));
//      }

      if (verbose)
        Log.info("returning annotations for " + comm.getId());
      return comm;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getDocumentation() throws TException {
    return "none";
  }

  @Override
  public AnnotationMetadata getMetadata() throws TException {
    return meta;
  }

  @Override
  public void shutdown() throws TException {
    model = null;
  }

  public static void serve(ExperimentProperties config) throws TTransportException {
    FNParseConcreteService impl = new FNParseConcreteService(config);
    AnnotateCommunicationService.Processor<Iface> proc = new AnnotateCommunicationService.Processor<>(impl);
    
    // DEBUG
    impl.echo = config.getBoolean("echo", false);
    impl.verbose = config.getBoolean("verbose", false);

    int port = config.getInt("port", 9090);
    TNonblockingServerTransport serverXport = new TNonblockingServerSocket(port);
    TNonblockingServer.Args args = new TNonblockingServer.Args(serverXport);
    args.protocolFactory(new TCompactProtocol.Factory());
    args.transportFactory(new TFramedTransport.Factory(Integer.MAX_VALUE));
    args.processorFactory(new TProcessorFactory(proc));
    args.maxReadBufferBytes = Long.MAX_VALUE;

    Log.info("starting server...");
    TServer server = new TNonblockingServer(args);
    server.serve();
  }
  
  public static void foo() throws Exception {
    File f = new File("/tmp/fnparse-concrete-service-debug-7945541799297194742.comm");
    Communication comm = new Communication();
    try (BufferedInputStream b = new BufferedInputStream(new FileInputStream(f))) {
      comm.read(new TCompactProtocol(new TIOStreamTransport(b)));
      System.out.println(comm.getId());
      
      for (SituationMentionSet sms : comm.getSituationMentionSetList()) {
        Log.info("sms: " + sms.getMetadata());
        for (SituationMention sm : sms.getMentionList()) {
          Log.info("sm: " + sm);
        }
      }

    }
  }

  public static void main(String[] as) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(as);
    
    boolean debug = config.getBoolean("debug", false);
//    if (debug) {
//      foo();
//      return;
//    }
    
//    serve(config);
    TimeMarker tm = new TimeMarker();
    FNParseConcreteService client = new FNParseConcreteService(config);
    List<File> inputTgz = config.getFileGlob("input");
    File outputTgz = config.getFile("output");
    Log.info("reading from: " + inputTgz);
    Log.info("writing to: " + outputTgz.getPath());
    try (OutputStream os = Files.newOutputStream(outputTgz.toPath());
        BufferedOutputStream bos = new BufferedOutputStream(os, 1024 * 8 * 24);
        TarArchiver archiver = new TarArchiver(new GzipCompressorOutputStream(bos))) {
      for (File f : inputTgz) {
        client.ec.increment("annotate/file");
        Log.info("processing " + f.getPath());
        try (InputStream is = new FileInputStream(f);
            TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
          while (iter.hasNext()) {
            Communication c = iter.next();
            Communication anno = client.annotate(c);
            
            if (debug) {
              File of = File.createTempFile("fnparse-concrete-service-debug-", ".comm");
              Log.info("writing to " + of.getPath());
              try (BufferedOutputStream b = new BufferedOutputStream(new FileOutputStream(of))) {
                anno.write(new TCompactProtocol(new TIOStreamTransport(b)));
              }
            }
            
            archiver.addEntry(new ArchivableCommunication(anno));
            if (tm.enoughTimePassed(15)) {
              Log.info("counts: " + client.ec);
            }
          }
        }
      }
    }
  }
}
