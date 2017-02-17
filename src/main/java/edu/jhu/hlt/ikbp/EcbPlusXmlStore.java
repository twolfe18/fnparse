package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Stores a bunch of XML wrappers in memory, allows sharing across modules.
 *
 * @author travis
 */
public class EcbPlusXmlStore {

  private File topicParent;
  private Map<String, EcbPlusXmlWrapper> docs;
  public boolean showReads = false;
  
  public EcbPlusXmlStore(ExperimentProperties config) {
    this(config.getExistingDir("data.ecbplus", new File("/home/travis/code/fnparse/data/parma/ecbplus/ECB+_LREC2014/ECB+")));
  }

  public EcbPlusXmlStore(File topicParent) {
    this.topicParent = topicParent;
    this.docs = new HashMap<>();
  }
  
  public File getTopicParent() {
    return topicParent;
  }
  
  public List<File> getTopicDirs() {
    List<File> dirs = new ArrayList<>();
    for (String f : topicParent.list()) {
      File ff = new File(topicParent, f);
      if (ff.isDirectory() && ff.getName().matches("\\d+"))
        dirs.add(ff);
    }
    return dirs;
  }
  
  public List<File> getDocs(File topic) {
    List<File> xmlFiles = new ArrayList<>();
    for (File f : topic.listFiles())
      if (f.isFile() && f.getPath().endsWith(".xml"))
        xmlFiles.add(f);
    return xmlFiles;
  }
  
  public void load() {
    for (String n : topicParent.list()) {
      if (!n.matches("\\d+"))
        continue;
      File root = new File(topicParent, n);
      if (!root.isDirectory())
        continue;
      for (File f : root.listFiles()) {
        if (!f.getName().endsWith(".xml"))
          continue;
        if (showReads)
          Log.info("reading from " + f.getPath());
        EcbPlusXmlWrapper xml = new EcbPlusXmlWrapper(f);
        docs.put(xml.getXmlFile().getName().replaceAll(".xml", ""), xml);
      }
    }
  }
  
  public void clear() {
    docs.clear();
  }
  
  public EcbPlusXmlWrapper get(File xmlFile) {
    String docId = xmlFile.getName().replaceAll(".xml", "");
    return get(docId);
  }

  public EcbPlusXmlWrapper get(String docId) {
    EcbPlusXmlWrapper xml = docs.get(docId);
    if (xml == null) {
      String topic = EcbPlusUtil.getTopic(docId);
      File f = new File(new File(topicParent, topic), docId + ".xml");
      if (!f.isFile())
        throw new RuntimeException("not found: " + f.getPath());
      if (showReads)
        Log.info("reading from " + f.getPath());
      xml = new EcbPlusXmlWrapper(f);
      Object old = docs.put(docId, xml);
      assert old == null;
    }
    return xml;
  }
  
  public static List<String> select(int[] indices, List<String> from) {
    List<String> out = new ArrayList<>(indices.length);
    for (int i : indices)
      out.add(from.get(i));
    return out;
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

    // Iterate over XML
//    EcbPlusXmlStore store = new EcbPlusXmlStore(config);
//    for (File topic : store.getTopicDirs()) {
//      for (File f : store.getDocs(topic)) {
//        if (!"2_2ecb.xml".equals(f.getName()))
//          continue;
//        EcbPlusXmlWrapper xml = store.get(f);
//
//        // Show all the ACTION_* and NEG_ACTION_* lexicalizations
//        List<String> toks = xml.getTokens();
//        for (Node n : xml.getNodes()) {
//          if (n.isGrounded() && n.type.contains("ACTION_")) {
//            System.out.println(n + "\t" + select(n.t_id, toks));
//          }
//        }
//      }
//    }
    
    // Iterate over Concrete
    TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory());
    File commRoot = new File("data/parma/ecbplus/ECB+_LREC2014/concrete-parsey-and-stanford");
    for (File f : commRoot.listFiles()) {
      if (!f.getName().endsWith(".comm"))
        continue;
      System.out.println(f);
      Communication c = new Communication();
      byte[] bs = FileUtil.readBytes(f);
      deser.deserialize(c, bs);
      
      System.out.println(c.getId());
//      System.out.println(c.getSituationMentionSetListSize());
      System.out.println(c.getSituationSetListSize());
      System.out.println();
    }

  }

}
