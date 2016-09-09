package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.ExperimentProperties;
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
    this(config.getExistingDir("data.ecbplus", new File("data/parma/ecbplus/ECB+_LREC2014/ECB+")));
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
}
