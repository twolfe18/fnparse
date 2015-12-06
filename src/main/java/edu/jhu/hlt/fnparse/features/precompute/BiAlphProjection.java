package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Read in some int features and a bialph and spit out some int features (in a
 * new domain). Can be used to filter feature files.
 *
 * NOTE: This is one of the few cases where ALPH_AS_TRIVIAL_BIALPH in
 * {@link LineMode} is actually useful.
 *
 * NOTE: This does not change the order over templates, so for example, if the
 * line of the feature file are sorted by template before the projection they
 * will not be after the projection unless the projection is the identity or
 * you get lucky.
 *
 * NOTE: If the bialph does not contain all the needed entries (e.g. there are
 * ints in a feature file which have no corresponding row that match an old int),
 * then drop that feature.
 *
 * @author travis
 */
public class BiAlphProjection {

  /** Maps oldInt -> newInt using a {@link BiAlph} */
  static class BiAlphIntMapper {
    private BiAlph bialph;
    public BiAlphIntMapper(File bialphFile, LineMode lineMode) {
      bialph = new BiAlph(bialphFile, lineMode);
    }

    /** Doesn't append a newline to output */
    public void replace(String inputLine, StringBuilder outputLine) {
      String[] toks = inputLine.split("\t");
      for (int i = 0; i < 5; i++) {
        if (i > 0) outputLine.append('\t');
        outputLine.append(toks[i]);
      }
      for (int i = 5; i < toks.length; i++) {
        IntPair tf = BiAlphMerger.parseTemplateFeature(toks[i]);
        int newTemplate = bialph.mapTemplate(tf.first);
        if (newTemplate < 0)
          continue;
        int newFeature = bialph.mapFeature(tf.first, tf.second);
        if (newFeature < 0)
          continue;
        outputLine.append("\t" + newTemplate + ":" + newFeature);
      }
    }

    public void replace(File inputFile, File outputFile, boolean append) throws IOException {
      TimeMarker tm = new TimeMarker();
      Log.info(inputFile.getPath() + "  ==>  " + outputFile.getPath() + "  append=" + append);
      try (BufferedReader r = FileUtil.getReader(inputFile);
          BufferedWriter w = FileUtil.getWriter(outputFile, append)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          StringBuilder sb = new StringBuilder();
          replace(line, sb);
          w.write(sb.toString());
          w.newLine();

          if (tm.enoughTimePassed(15)) {
            Log.info("processed " + tm.numMarks()
              + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
              + Describe.memoryUsage());
          }
        }
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    BiAlphIntMapper m = new BiAlphIntMapper(
        config.getExistingFile("inputBialph"),
        LineMode.valueOf(config.getString("lineMode")));

    // Two modes (wrt features, both take an alphabet):
    // 1) file -> file
    // 2) dir -> dir
    boolean append = config.getBoolean("append", false);
    String inFileKey = "inputFeatures";
    if (config.containsKey(inFileKey)) {
      Log.info("file->file mode");
      File in = config.getExistingFile(inFileKey);
      File out = config.getFile("outputFeatures");
      assert !out.isDirectory();
      m.replace(in, out, append);
    } else {
      Log.info("dir->dir mode");
      String featuresGlob = config.getString("featuresGlob", "glob:**/*");
      File out = config.getExistingDir("outputFeatures");
      if (!featuresGlob.isEmpty()) {
        File featuresParent = config.getExistingDir("featuresParent");
        PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
        Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
            if (pm.matches(path)) {
              File of = new File(out, path.getFileName().toString());
              Log.info("mapping features: " + path.toFile().getPath() + "  ==>  " + of.getPath() + "\t" + Describe.memoryUsage());
              m.replace(path.toFile(), of, append);
            }
            return FileVisitResult.CONTINUE;
          }
          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
          }
        });
      }
    }
    Log.info("done");
  }
}
