
# A python wrapper around SGE used to efficiently and paralleledly
# merge alphabets into smaller and coherent bialphs.
#
# Author: Travis Wolfe <twolfe18@gmail.com>
# Date: Sep. 16, 2015

import glob, sys, os, math, subprocess, shutil, re


def qsub_and_parse_jid(command):
  s = subprocess.check_output(command)
  # Your job 443510 ("foo-bar") has been submitted
  m = re.search('^Your job (\d+) \("(.+?)"\) has been submitted$', s)
  if m:
    jid = int(m.group(1))
    name_maybe_trunc = m.group(2)
    print '  launched job', jid
    return jid
  else:
    raise Exception('couldn\'t parse qsub output: ' + s)


class WorkingDir:
  def __init__(self, path, jar, log_dir='sge-logs', jar_name='all-in-one.jar'):
    self.path = path
    if not os.path.isdir(path):
      os.makedirs(path)
    self.log_dir = os.path.join(path, log_dir)
    if not os.path.isdir(self.log_dir):
      os.makedirs(self.log_dir)
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
  This class creates the jobs (qsub calls to edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger)
  needed to merge a whole bunch of alphabets.
  1) Holds state like working_dir, job id counter
  2) Provides some SGE specific functionality like parsing job ids upon launching a job
  '''
  def __init__(self, alph_glob, working_dir, mock=False):
    self.alph_glob = alph_glob
    self.working_dir = working_dir
    self.job_counter = 0

    # If mock=True, then don't actually qsub anything, just pretend as if you did
    self.mock = mock

    self.bialph_dir = os.path.join(working_dir.path, 'bialphs')
    if not os.path.isdir(self.bialph_dir):
      os.makedirs(self.bialph_dir)
    print 'putting bialphs in', self.bialph_dir
  
  def start(self):
    # Find all of the alphabets
    alphs = glob.glob(self.alph_glob)
    print 'merging', len(alphs), 'alphabets'
    
    # Build the computation tree Part 1:
    # Leaves -- alph => bialph
    interval = 15
    buf = []
    self.name2input = {}
    for i, a in enumerate(alphs):
      n = 'shard' + str(i)
      buf.append(Merge(self, alphabet_file=a, name=n))
      self.name2input[n] = a
      if len(buf) % interval == 0:
        print 'submitted', len(buf), 'jobs for creating bialphs'

    # Build the computation tree Part 2:
    # Recursively merge -- (bialph, bialph) => bialph
    nm = 0
    while len(buf) > 1:
      # Find the two smallest nodes and merge them.
      b1 = buf.pop()
      b2 = buf.pop()
      b3 = Merge(self, left_child=b1, right_child=b2)
      buf.insert(0, b3)
      nm += 1
      if nm % interval == 0:
        print 'submitted', nm, 'jobs for merging bialphs'

    print 'done launching jobs, created', self.job_counter, 'jobs'

    return buf.pop()

  def output(self, depth, i):
    return os.path.join(self.bialph_dir, "bialph_d%d_%s.txt" % (depth, i))

  def make_merge_job(self, dep1, dep2, in1, in2, out1, out2):
    ''' returns a jid '''
    name = 'merge-' + str(self.job_counter)
    self.job_counter += 1
    command = ['qsub']
    command += ['-N', name]
    command += ['-o', self.working_dir.log_dir]
    command += ['-hold_jid', "%d,%d" % (dep1, dep2)]
    command += ['scripts/precompute-features/bialph-merge.sh']
    command += [in1, in2, out1, out2, self.working_dir.jar_file]
    print 'make_merge_job:', '\n\t'.join(command)
    if self.mock:
      return self.job_counter + 40000
    jid = qsub_and_parse_jid(command)
    return jid

  def make_create_job(self, alphabet_filename, bialph_filename):
    ''' returns a jid '''
    name = 'create-' + str(self.job_counter)
    self.job_counter += 1
    command = ['qsub']
    command += ['-N', name]
    command += ['-o', self.working_dir.log_dir]
    command += ['scripts/precompute-features/bialph-create.sh']
    command += [alphabet_filename, bialph_filename]
    print 'make_create_job:', '\n\t'.join(command)
    if self.mock:
      return self.job_counter + 40000
    jid = qsub_and_parse_jid(command)
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

def bialph2alph(in_file, out_file, dep_jid, working_dir, mock=False):
  command = ['qsub']
  command += ['-N', 'make-final-alph']
  command += ['-o', working_dir.log_dir]
  command += ['-hold_jid', str(dep_jid)]
  command += ['scripts/precompute-features/bialph-to-alph.sh']
  command += [in_file, out_file]
  print 'Projecting a bialph down to an alph:'
  print '\n\t'.join(command)
  if not mock:
    jid = qsub_and_parse_jid(command)
    return jid

def make_bialph_projection_job(feature_file, bialph_file, output_feature_file, dep_jid, working_dir, mock=False):
  command = ['qsub']
  command += ['-hold_jid', str(dep_jid)]
  command += ['-o', working_dir.log_dir]
  command += ['scripts/precompute-features/bialph-projection.sh']
  command += [feature_file, bialph_file, output_feature_file, working_dir.jar_file]
  print 'Projecting features through a bialph to build a coherent feature file:'
  print '\n\t'.join(command)
  if not mock:
    jid = qsub_and_parse_jid(command)
    return jid

if __name__ == '__main__':
  m = False # mock
  p = '/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b'
  alph_glob = os.path.join(p, 'raw-shards/job-*-of-400/template-feat-indices.txt.gz')
  merge_bialph_dir = os.path.join(p, 'merged-bialphs')
  jar = 'target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar'

  # Create and merge all of the bialphs
  merge_wd = WorkingDir(merge_bialph_dir, jar)
  merger = BiAlphMerger(alph_glob, merge_wd, mock=m)
  root = merger.start()

  # Choose any bialph to build the alph with (they all have the same first 4 columns)
  bialph = None
  bialph_dep_jid = None
  print 'results should be in...'
  for (name, depth, jid) in root.items:
    bialph = merger.output(depth, name)
    bialph_dep_jid = jid
    break

  # Project a bialph to a single compressed alphabet for later
  proj_wd = WorkingDir(os.path.join(p, 'coherent-shards'), jar)
  alph = os.path.join(proj_wd.path, 'alphabet.txt.gz')
  bialph2alph(bialph, alph, bialph_dep_jid, proj_wd, mock=m)

  # Project the feature sets into this new universal alphabet
  feat_dir = proj_wd.mkdir('features')
  for (name, depth, jid) in root.items:
    bialph = merger.output(depth, name)
    alph = merger.name2input[name]
    dn = os.path.dirname(alph)
    features = os.path.join(dn, 'features.txt.gz')
    output_features = os.path.join(feat_dir, name + '.txt.gz')
    make_bialph_projection_job(features, bialph, output_features, jid, proj_wd, mock=m)

  print
  print 'Once all of the jobs are done, you probably want to cleanup (delete) the intermediate data in:'
  print merge_wd
  print
  print 'The final output is in:'
  print proj_wd
  print




