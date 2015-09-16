
import glob, sys, os, math, subprocess, shutil

class BiAlphMerger:
  '''
  This class creates the jobs (qsub calls to edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger)
  needed to merge a whole bunch of alphabets.
  1) Holds state like working_dir, job id counter
  2) Provides some SGE specific functionality like parsing job ids upon launching a job
  '''
  def __init__(self, alph_glob, working_dir, jar_file, copy_jar=True):
    self.alph_glob = alph_glob
    self.working_dir = working_dir
    self.job_counter = 0
    if copy_jar:
      self.jar_file = os.path.join(working_dir, 'fnparse.jar')
      print 'copying jar file to safe place:'
      print jar_file, ' => ', self.jar_file
      shutil.copyfile(jar_file, self.jar_file)
    else:
      print 'using jar file in place:', jar_file
      self.jar_file = jar_file
  
  def start(self):
    # Find all of the alphabets
    alphs = glob.glob(alph_glob)
    print 'merging', len(alphs), 'alphabets'
    
    # Build the computation tree
    buf = []
    for a in alphs:
      buf.append(Merge(self, alphabet_file=a))
    while len(buf) > 1:
      # Find the two smallest nodes and merge them.
      b1 = buf.pop()
      b2 = buf.pop()
      b3 = Merge(self, left_child=b1, right_child=b2)
      buf.insert(0, b3)
    print 'launched', self.job_counter, 'jobs'

    root = buf.pop()
    print 'the root node is:', root
    return root

  def output(self, depth, i):
    return os.path.join(self.working_dir, "bialph_d%d_%d" % (depth, i))

  def make_merge_job(self, dep1, dep2, in1, in2, out1, out2):
    ''' returns a (name, jid) '''
    name = 'merge-' + str(self.job_counter)
    self.job_counter += 1
    command = ['qsub']
    command += ['-N', name]
    command += ['-hold_jid', str(dep1)]
    command += ['-hold_jid', str(dep2)]
    command += ['scripts/precompute-features/bialph-merge.sh']
    command += [in1, in2, out1, out2, self.jar_file]
    s = subprocess.check_output(command)
    # Your job 443510 ("foo-bar") has been submitted
    m = re.search('^Your job (\d+) \("(.+?)"\) has been submitted$', s)
    if m:
      jid = int(m.group(1))
      name_maybe_trunc = m.group(2)
      return (name, jid)
    else:
      raise Exception('couldn\'t parse qsub output: ' + s)

  def make_bialph_jobs(self, alphabet_filename, bialph_filename):
    ''' returns a (name, jid) '''
    name = 'create-' + str(self.job_counter)
    self.job_counter += 1
    command = ['qsub']
    command += ['-N', name]
    command += ['scripts/precompute-features/bialph-create.sh']
    command += [alphabet_filename, bialph_filename]
    s = subprocess.check_output(command)
    # Your job 443510 ("foo-bar") has been submitted
    m = re.search('^Your job (\d+) \("(.+?)"\) has been submitted$', s)
    if m:
      jid = int(m.group(1))
      name_maybe_trunc = m.group(2)
      return (name, jid)
    else:
      raise Exception('couldn\'t parse qsub output: ' + s)



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
      self.__init_leaf(kwargs['alphabet_file'])

  def __init_leaf(self, alphabet_file):
    self.alphabet_file = alphabet_file
    (name, jid) = self.sge.make_bialph_job(alphabet_file)
    self.items = [(name, 0, jid)]

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
      depth3 = math.max(depth1, depth2) + 1
      in1 = self.sge.output(depth1, name1)
      in2 = self.sge.output(depth2, name2)
      out1 = self.sge.output(depth3, name1)
      out2 = self.sge.output(depth3, name2)
      jid3 = self.sge.make_merge_job(jid1, jid2, in1, in2, out1, out2)
      self.items.append( (name3, depth3, jid3) )

if __name__ == '__main__':
  p = '/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b'
  g = os.path.join(p, 'raw-shards/job-*-of-400')
  o = os.path.join(p, 'merged-alphs')
  j = 'target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar'
  merger = BiAlphMerger(g, o)
  root = merger.start()
  print 'results should be in...'
  for (name, depth, jid) in root.items:
    print self.output(depth, name)

