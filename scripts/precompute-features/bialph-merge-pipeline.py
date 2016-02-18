
# A python wrapper around SGE used to efficiently and paralleledly
# merge alphabets into smaller and coherent bialphs.
#
# Author: Travis Wolfe <twolfe18@gmail.com>
# Date: Sep. 16, 2015

import glob, itertools, sys, os, math, subprocess, shutil, re, collections, time

#SUF = '.gz'
SUF = '.bz2'

def qsub_and_parse_jid(command):
  s = subprocess.check_output(command)
  # SGE: Your job 443510 ("foo-bar") has been submitted
  # SLURM: Submitted batch job 3976002
  m = re.search('^Your job (\d+) \("(.+?)"\) has been submitted$', s)
  if m:
    jid = int(m.group(1))
    name_maybe_trunc = m.group(2)
    print '  (qsub) launched job', jid
    return jid
  else:
    raise Exception('couldn\'t parse qsub output: ' + s)

def jcl_and_parse_jid(command):

  # I seem to be having some problems with slurm recognizing and
  # respecting my dependencies. Its possible that this is because
  # its missing/dropping jobs. This is a last resort... it works with COE/SGE...
  time.sleep(0.5)

  # This is the actual command (that calls slurm)
  print 'command:', command
  c = subprocess.check_output(command)
  # This submits the job and prints the jid
  print 'c:', c
  s = subprocess.check_output(c, shell=True)
  print 's:', s
  m = re.search('^(\d+)$', s)
  if m:
    jid = int(m.group(1))
    print '  (jcl) launched job', jid
    return jid
  else:
    raise Exception('couldn\'t parse jcl output: ' + s)


class WorkingDir:
  def __init__(self, path, jar, log_dir='sge-logs', jar_name='all-in-one.jar'):
    self.path = path
    if not os.path.isdir(path):
      os.makedirs(path)
    self.log_dir = os.path.join(path, log_dir)
    if not os.path.isdir(self.log_dir):
      os.makedirs(self.log_dir)

    if jar is None:
      print 'WARNING: no jar provided for WorkingDir in', path
      self.jar_file = 'noJar'
    else:
      if not os.path.isfile(jar):
        raise Exception('not a jar file: ' + jar)
      self.jar_file = os.path.join(path, jar_name)
      print 'copying jar to safe place:'
      print '\t', jar, ' ==> ', self.jar_file
      shutil.copyfile(jar, self.jar_file)

  def mkdir(self, name):
    p = os.path.join(self.path, name)
    if not os.path.isdir(p):
      os.makedirs(p)
    return p

  def __str__(self):
    return self.path


class BiAlphMerger:
  '''
  This class creates the jobs (jcl/qsub calls to edu.jhu.hlt.fnparse.features.precompute.BiAlphMerger)
  needed to merge a whole bunch of alphabets.
  0) Stores Merge objects
  1) Holds state like working_dir, job id counter
  2) Provides some SGE specific functionality like parsing job ids upon launching a job
  '''
  def __init__(self, alphs, working_dir, mock=False, jcl=True):
    self.alphs = alphs
    self.working_dir = working_dir
    self.job_counter = 0
    self.jcl = jcl

    # If mock=True, then don't actually qsub/jcl anything, just pretend as if you did
    self.mock = mock

    self.bialph_dir = os.path.join(working_dir.path, 'bialphs')
    if not os.path.isdir(self.bialph_dir):
      os.makedirs(self.bialph_dir)
    print 'putting bialphs in', self.bialph_dir
  
  def start(self, cleanup=True):
    # Build the computation tree Part 1:
    # Leaves -- alph => bialph
    print 'merging', len(self.alphs), 'alphabets'
    interval = 15
    buf = []
    self.name2input = {}
    for i, a in enumerate(self.alphs):
      n = 'shard' + str(i)
      buf.append(Merge(self, alphabet_file=a, name=n))
      self.name2input[n] = a
      if len(buf) % interval == 0:
        print 'submitted', len(buf), 'jobs for creating bialphs'

    # Build the computation tree Part 2:
    # Recursively merge -- (bialph, bialph) => bialph
    nm = 0
    merge_jobs = []
    while len(buf) > 1:
      # Find the two smallest nodes and merge them.
      b1 = buf.pop()
      b2 = buf.pop()
      b3 = Merge(self, left_child=b1, right_child=b2)
      buf.insert(0, b3)
      nm += 1
      if nm % interval == 0:
        print 'submitted', nm, 'jobs for merging bialphs'
      merge_jobs.append(b3)

    # Intermediate results are turning out to be sort of huge, so remove
    # bialphs by depth as soon as they are not needed. This code computes the
    # set of jobs that depend on a given intermediate file, and then creates a
    # job to delete that file when all of the dependent jobs are done.
    if cleanup:
      f2deps = collections.defaultdict(list)
      for j in merge_jobs:
        for f, deps in j.f2deps.iteritems():
          f2deps[f] += deps
      for f, deps in f2deps.iteritems():
        n = os.path.basename(f)
        if self.jcl:
          command = ['jcl']
          command.append('rm ' + f)
          command.append("--job_name=cleanup-%s" % (n,))
          command.append("--log_directory=%s" % (self.working_dir.log_dir,))
          command.append("--depends_on=%s" % (','.join(map(str, deps))))
        else:
          command = ['qsub']
          command += ['-N', 'cleanup-' + n]
          command += ['-o', self.working_dir.log_dir]
          command += ['-b', 'y']
          command += ['-j', 'y']
          command += ['-hold_jid', ','.join(map(str, deps))]
          command += ['rm', f]
        print 'Creating job to remove', n, 'after it is done being used by', deps
        print '\n\t'.join(command)
        if not self.mock:
          jid = self.submit_and_parse_jid(command)
          print jid

    print 'done launching jobs, created', self.job_counter, 'jobs'
    return buf.pop()

  def output(self, depth, i):
    global SUF
    return os.path.join(self.bialph_dir, "bialph_d%d_%s.txt%s" % (depth, i, SUF))

  def interesting_info(self, inputname, default_return_val):
    # bialph_d4_shard98.txt.gz
    p = re.compile('.*_(d\d+_shard\d+).*')
    m = re.match(p, inputname)
    if m:
      return m.group(1)

    # raw-shards/job-0-of-256/features.txt.gz
    p = re.compile('.*job-(\d+)-of-(\d+).*')
    m = re.match(p, inputname)
    if m:
      return m.group(1)

    print '[interesting info] failed for:', inputname
    return default_return_val

  def make_merge_job(self, dep1, dep2, in1, in2, out1, out2):
    ''' returns a jid '''
    i1 = self.interesting_info(in1, None)
    i2 = self.interesting_info(in2, None)
    if i1 and i2:
      name = "merge-%s-%s" % (i1, i2)
    else:
      name = 'merge-' + str(self.job_counter)
    self.job_counter += 1
    if self.jcl:
      command = ['jcl']
      command.append(' '.join(['scripts/precompute-features/bialph-merge.sh', \
        in1, in2, out1, out2, self.working_dir.jar_file]))
      command.append("--job_name=%s" % (name,))
      command.append("--log_directory=%s" % (self.working_dir.log_dir,))
      command.append("--depends_on=%d,%d" % (dep1, dep2))
    else:
      command = ['qsub']
      command += ['-N', name]
      command += ['-o', self.working_dir.log_dir]
      command += ['-hold_jid', "%d,%d" % (dep1, dep2)]
      command += ['scripts/precompute-features/bialph-merge.sh']
      command += [in1, in2, out1, out2, self.working_dir.jar_file]
    print 'make_merge_job:', '\n\t'.join(command)
    if self.mock:
      jid = self.job_counter + 40000
    else:
      jid = self.submit_and_parse_jid(command)
    print 'jid:', jid
    return jid

  def submit_and_parse_jid(self, command):
    if self.jcl:
      return jcl_and_parse_jid(command)
    else:
      return qsub_and_parse_jid(command)

  def make_create_job(self, alphabet_filename, bialph_filename):
    ''' returns a jid '''
    i = self.interesting_info(bialph_filename, None)
    if i:
      name = "create-%s" % (i,)
    else:
      name = 'create-' + str(self.job_counter)
    self.job_counter += 1
    if self.jcl:
      command = ['jcl']
      command.append(' '.join(['scripts/precompute-features/alph-to-bialph.sh', alphabet_filename, bialph_filename]))
      command.append("--job_name=%s" % (name,))
      command.append("--log_directory=%s" % (self.working_dir.log_dir,))
    else:
      command = ['qsub']
      command += ['-N', name]
      command += ['-o', self.working_dir.log_dir]
      command += ['scripts/precompute-features/alph-to-bialph.sh']
      command += [alphabet_filename, bialph_filename]
    print 'make_create_job:', '\n\t'.join(command)
    if self.mock:
      jid = self.job_counter + 40000
    else:
      jid = self.submit_and_parse_jid(command)
    print 'jid', jid
    return jid


class Merge:
  '''
  A tree node that represents a set of bialphs which all have the same domain
  (i.e. the set of (template,feature) strings they have indexed is the same)
  and are sorted by (template,feature) string.
  __init_leaf is the base case and __init_merge maintains this invariant.

  The tree represents how the computation is to be completed. Job dependecies come
  from the childrens' item field.

  self.items is the list of bialphs/jobs required to build this node (and thus
  the self of alphabets this node covers).

  The set of alphabets that this tree represents can be recovered by unioning
  the self.alphabet_file field over all the leaves in this tree.

  Takes an BiAlphMerger for additional functionality not pertinent to this class.
  '''
  def __init__(self, *args, **kwargs):
    self.sge = args[0]
    if 'left_child' in kwargs:
      self.__init_merge(kwargs['left_child'], kwargs['right_child'])
    else:
      self.__init_leaf(kwargs['alphabet_file'], kwargs['name'])

  def __init_leaf(self, alphabet_file, name):
    self.alphabet_file = alphabet_file
    depth = 0
    bialph_filename = self.sge.output(depth, name)
    jid = self.sge.make_create_job(alphabet_file, bialph_filename)
    self.items = [(name, depth, jid)]

  def __init_merge(self, left_child, right_child):
    # Gaurantee the left_child has at least as many items as right_child
    if len(left_child.items) < len(right_child.items):
      t = left_child
      left_child = right_child
      right_child = t
    self.left_child = left_child
    self.right_child = right_child
    self.items = []

    # Map from file (bialph) to list of jids which depend on the key
    self.f2deps = collections.defaultdict(list)

    nr = len(right_child.items)
    for i, (name1, depth1, jid1) in enumerate(left_child.items):
      (name2, depth2, jid2) = right_child.items[i % nr]
      depth3 = max(depth1, depth2) + 1
      in1 = self.sge.output(depth1, name1)
      in2 = self.sge.output(depth2, name2)
      out1 = self.sge.output(depth3, name1)
      out2 = self.sge.output(depth3, name2)
      jid3 = self.sge.make_merge_job(jid1, jid2, in1, in2, out1, out2)
      self.items.append( (name1, depth3, jid3) )
      if i < nr:
        # If we've looped around, then we only need to keep the output of
        # the left item, the right item has already been computed.
        self.items.append( (name2, depth3, jid3) )

      # Update file<-job dependencies
      self.f2deps[in1].append(jid3)
      self.f2deps[in2].append(jid3)

  def input_files(self):
    ''' generator of the input files to be merged by this node '''
    for (name, depth, jid) in self.items:
      yield self.sge.output(depth, name)

  def jobs(self):
    ''' generator of the job ids created by this node '''
    for (name, depth, jid) in self.items:
      yield jid

def bialph2alph(in_file, out_file, dep_jid, working_dir, mock=False, jcl=True):
  # TODO Add a command to compute feature cardinalties:
  # awk -F"\t" '{print $3}' <$INPUT | uniq -c >$OUTPUT
  if jcl:
    command = ['jcl']
    command.append(' '.join(['scripts/precompute-features/bialph-to-alph.sh', in_file, out_file]))
    command.append('--job_name=make-final-alph')
    command.append("--log_directory=%s" % (working_dir.log_dir,))
    command.append("--depends_on=%d" % (dep_jid,))
  else:
    command = ['qsub']
    command += ['-N', 'make-final-alph']
    command += ['-o', working_dir.log_dir]
    command += ['-hold_jid', str(dep_jid)]
    command += ['scripts/precompute-features/bialph-to-alph.sh']
    command += [in_file, out_file]
  print 'Projecting a bialph down to an alph:'
  print '\n\t'.join(command)
  if mock:
    return -1
  elif jcl:
    return jcl_and_parse_jid(command)
  else:
    return qsub_and_parse_jid(command)

def make_bialph_projection_job(feature_file, bialph_file, output_feature_file, dep_jid, working_dir, cleanup=True, mock=False, jcl=True):
  r = 'Y' if cleanup else 'N'
  if jcl:
    command = ['jcl']
    command.append(' '.join(['scripts/precompute-features/bialph-proj-features.sh', \
      feature_file, bialph_file, output_feature_file, working_dir.jar_file, r]))
    command.append("--depends_on=%d" % (dep_jid,))
    command.append("--log_directory=%s" % (working_dir.log_dir,))
    command.append('--job_name=feat-proj')
    command.append('--memory=4G')
  else:
    command = ['qsub']
    command += ['-N', 'feat-proj']
    command += ['-l', 'mem_free=4G,num_proc=1,h_rt=24:00:00']
    command += ['-hold_jid', str(dep_jid)]
    command += ['-o', working_dir.log_dir]
    command += ['scripts/precompute-features/bialph-proj-features.sh']
    command += [feature_file, bialph_file, output_feature_file, working_dir.jar_file, r]
  print 'Projecting features through a bialph to build a coherent feature file:'
  print '\n\t'.join(command)
  if mock:
    return -1
  elif jcl:
    return jcl_and_parse_jid(command)
  else:
    return qsub_and_parse_jid(command)

def main():
  # TODO Generalize these inputs to be suitable for a library.
  global SUF
  m = False # mock
  j = False # jcl
  cu = True  # cleanup
  if len(sys.argv) != 5:
    print 'please provide:'
    print '1) a working dir, e.g. /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b'
    print '2) how many shards are in the WD/raw-shards directory, e.g. 400 when job dirs are named job-*-of-400'
    print '3) a compression suffix, e.g. ".gz" or ".bz2"'
    print '4) a JAR file'
    sys.exit(1)
  #p = '/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b'
  #p = '/export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a'
  p = sys.argv[1]
  shards = str(int(sys.argv[2]))
  SUF = sys.argv[3]
  #jar = 'target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar'
  jar = sys.argv[4]

  alph_glob = os.path.join(p, 'raw-shards/job-*-of-' + shards + '/template-feat-indices.txt' + SUF)
  print 'alph_glob:', alph_glob
  alphs = glob.glob(alph_glob)
  print 'found', len(alphs), 'alphs'

  merge_bialph_dir = os.path.join(p, 'merged-bialphs')
  print 'merge_bialph_dir:', merge_bialph_dir

  if not os.path.isfile(jar):
    print 'JAR is not a file:', jar
    sys.exit(-1)

  # Create and merge all of the bialphs
  merge_wd = WorkingDir(merge_bialph_dir, jar)
  merger = BiAlphMerger(alphs, merge_wd, mock=m, jcl=j)
  root = merger.start(cleanup=cu)

  # Project the feature sets into this new universal alphabet
  #
  # NOTE: The shard numbers (e.g. shard0.txt.gz) WILL NOT match up
  # with the input shard numbers (e.g. job-0-of-256).
  #
  proj_wd = WorkingDir(os.path.join(p, 'coherent-shards'), jar)
  feat_dir = proj_wd.mkdir('features')
  for i, (name, depth, jid) in enumerate(root.items):
    # Job dependency for this bialph -> alph job.
    dep = jid

    # Project a bialph to a single compressed alphabet for later
    if i == 0:
      # Choose a bialph to build the final alph with (they all have the same first 4 columns)
      bialph = merger.output(depth, name)
      alph = os.path.join(proj_wd.path, 'alphabet.txt' + SUF)
      proj_alph_jid = bialph2alph(bialph, alph, jid, proj_wd, mock=m, jcl=j)
      # This job depends on jid, so we can set the current job to wait for this
      dep = proj_alph_jid

    bialph = merger.output(depth, name)
    alph = merger.name2input[name]
    dn = os.path.dirname(alph)
    features = os.path.join(dn, 'features.txt' + SUF)
    output_features = os.path.join(feat_dir, name + '.txt' + SUF)
    make_bialph_projection_job(features, bialph, output_features, dep, proj_wd, cleanup=cu, mock=m, jcl=j)

  print
  print 'Once all of the jobs are done, you probably want to remove directory housing the intermediate data:'
  print merge_wd
  print
  print 'The final output is in:'
  print proj_wd
  print

if __name__ == '__main__':
  #wd = WorkingDir('/tmp/travis/debug-bialph-merge', None)
  #bm = BiAlphMerger(['a', 'b', 'c'], wd, mock=True, jcl=False)
  #bm.start(cleanup=False)
  main()


