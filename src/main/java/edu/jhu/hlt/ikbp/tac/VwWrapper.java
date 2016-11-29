package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

public class VwWrapper implements AutoCloseable {
  public static final File VW_BINARY = new File("/usr/local/bin/vw");
  
  private File modelFile;
  private int port;
  private Socket sock;
  private BufferedWriter sockW;
  private BufferedReader sockR;
  private Process proc;
  
  public VwWrapper(File model, int port) throws Exception {
    Log.info("model=" + model.getPath() + " port=" + port);
    this.modelFile = model;
    this.port = port;
    ProcessBuilder pb = new ProcessBuilder(
        VW_BINARY.getPath(),
        "-i", model.getAbsolutePath(),
        "-t", "--daemon", "--quiet",
        "--port", String.valueOf(port),
        "--num_children", "1");
    proc = pb.start();
//    InputStreamGobbler stdout = new InputStreamGobbler(proc.getInputStream());
//    InputStreamGobbler stderr = new InputStreamGobbler(proc.getErrorStream());
//    stdout.start();
//    stderr.start();
    
    // If you don't sleep a bit, the socket connection will fail... ugly
    Thread.sleep(1 * 1000);
    
    // Open up socket to VW thread
    Log.info("connecting");
    sock = new Socket("localhost", port);
    sockW = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
    sockR = new BufferedReader(new InputStreamReader(sock.getInputStream()));
  }
  
  public File getModelFile() {
    return modelFile;
  }
  
  public int getPort() {
    return port;
  }
  
  public double predict(String line) {
    try {
      sockW.write(line);
      if (!line.endsWith("\n"))
        sockW.newLine();
      sockW.flush();
      String res = sockR.readLine();
      String[] tok = res.split("\\s+");
      return Double.parseDouble(tok[0]);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() throws Exception {
//    Log.info("closing...");
    sockR.close();
    sockW.close();
    sock.close();
//    Log.info("destroying...");
    proc.destroy();
//    proc.destroyForcibly();
//    Log.info("waiting...");
    int r = proc.waitFor();
    assert r == 0;
//    Log.info("r=" + r);
//    Log.info("done");
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File m = config.getExistingFile("examples.train", new File("data/parma/training_files/ecbplus/model_neg4.vw"));
    int port = config.getInt("port", 8094);
    VwWrapper vw = new VwWrapper(m, port);
    
    try (BufferedReader r = FileUtil.getReader(new File("data/parma/training_files/ecbplus/pairs_neg2.dev.txt"))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        double yhat = vw.predict(line);
        System.out.println(yhat + "\t" + line);
      }
    }
    
    vw.close();
  }
}
