# Author: Travis Wolfe <twolfe18@gmail.com>
# Date: September 25, 2015

# This class is designed to choose feature template based on how "similar" they appear from the names.
# We assume that we have a table of IG estimates from InformationGainProducts.

# The idea being that we have templates separated by '*'
# and each template also has some structure.
# We would like to encourage the system to choose EITHER "head1Word2" OR "head1Word3".
# That is, we should recognize that there was similarity in those template names, and say "don't choose two templates that are similar".

import os, codecs, re, collections, math, random, sys

TEMPLATE_NAME_BUG = False

NEW_FEATURE_FILE_FORMAT = True

def tokenize_feature(name):
  return sorted(name.split('*'))

def tokenize_template(name):
  name = name[0].upper() + name[1:]
  return re.findall('[A-Z][a-z]+\d*|[A-Z]+|\d+|\d+-[a-z]+', name)

def uppercase(c):
  return ord('A') <= ord(c) and ord(c) <= ord('Z')

def lowercase(c):
  return ord('a') <= ord(c) and ord(c) <= ord('z')

def digit(c):
  return ord('0') <= ord(c) and ord(c) <= ord('9')

def similarity_feature(f1, f2, show=False):
  if isinstance(f1, str):
    tf1 = list(tokenize_feature(f1))
  else:
    tf1 = f1
  if isinstance(f2, str):
    tf2 = list(tokenize_feature(f2))
  else:
    tf2 = f2
  if len(tf1) != len(tf2):
    if show:
      print 'mismatch!', tf1, tf2
    return 0.0
  # Product of similarities
  if show:
    print '\t  [sim_feat]', tf1, tf2
  sim = 1.0
  for t1, t2 in zip(tf1, tf2):
    st = similarity_template(t1, t2, show=show)
    if show:
      print '\t  [sim_feat]', st, t1, t2
    sim *= st
  sim = pow(sim, 1.0 / len(tf1))
  if show:
    print '\t  [sim_feat end]', sim, len(tf1)
  return sim

def similarity_template(t1, t2, show=False):
  tt1 = tokenize_template(t1)
  tt2 = tokenize_template(t2)
  l = levenshtein(tt1, tt2)
  norm_dist = float(l) / min(len(tt1), len(tt2))
  smooth = 1e-2
  if show:
    print "\t    [sim_temp] norm_dist=%.2f lev=%.2f tt1=%s tt2=%s" % (norm_dist, l, tt1, tt2)
  return (1 + smooth) / (norm_dist + smooth)

def levenshtein(s1, s2):
  table = collections.defaultdict(int)
  for i, c1 in enumerate(s1):
    for j, c2 in enumerate(s2):
      if i == 0 and j == 0: table[i,j] = (0 if c1 == c2 else 1)
      elif i == 0: table[i,j] = j
      elif j == 0: table[i,j] = i
      else:
        a = table[i-1, j-1] + (0 if c1 == c2 else 1)
        b = table[i-1, j] + 1
        c = table[i, j-1] + 1
        table[i,j] = min(a, b, c)
  return table[len(s1)-1, len(s2)-1]

def rand_prod(templates, n=3):
  random.shuffle(templates)
  i = random.randint(1, n)
  return templates[:i]

def debug():
  f = codecs.open('data/debugging/template-names.txt', 'r', 'utf-8')
  templates = [x.strip() for x in f.readlines()]
  f.close()
  #templates = ['span1Width/3']

  #print templates
  #for t in templates:
  #  print t, tokenize_template(t)

  #n = len(templates)
  #for i in range(n - 1):
  #  for j in range(i + 1, n):
  #    tti = tokenize_template(templates[i])
  #    ttj = tokenize_template(templates[j])
  #    s = similarity_template(templates[i], templates[j])
  #    print "%.2f  %s  %s  %s  %s" % (s, templates[i], templates[j], tti, ttj)

  for i in range(100):
    f1 = '*'.join(rand_prod(templates))
    f2 = '*'.join(rand_prod(templates))
    print "%.2f\t%s    %s" % (similarity_feature(f1, f2), f1, f2)

class Feature:
  def __init__(self, line, rank=None, template_name_bug=False):
    ar = line.rstrip().split('\t')
    if NEW_FEATURE_FILE_FORMAT:
      self.rank = rank
      self.score = float(ar[0])
      self.ig = float(ar[1])
      self.hx = float(ar[2])
      self.selectivity = float(ar[3])
      self.count = int(ar[4])
      self.order = int(ar[5])
      self.int_templates = map(int, ar[6].split('*'))
      self.str_templates = ar[7].split('*')
      self.order = len(self.int_templates)
      assert self.order == len(self.str_templates)
      if len(ar) > 8:
        self.restrict = ar[8]
    else:
      assert len(ar) >= 7, "ar=%s" % (str(ar))
      self.rank = rank
      self.score = float(ar[0])
      self.ig = float(ar[1])
      self.hx = float(ar[2])
      self.selectivity = float(ar[3])
      self.order = int(ar[4])
      self.int_templates = map(int, ar[5].split('*'))
      self.str_templates = ar[6].split('*')
      if template_name_bug:
        self.str_templates = map(undo_template_name_bug, self.str_templates)
      assert self.order == len(self.int_templates)
      assert self.order == len(self.str_templates)
      #print 'just read', self.str_like_input()

      if len(ar) >= 8:
        self.count = int(ar[7])
        if len(ar) >= 9:
          # TODO Can be None, frame, or (frame,role)
          # TODO Try out this as a list of (key,value) pairs, e.g. [('frame', 1887), ('role', 22)]
          # Every time you run combine-mutual-information.py, we strip one off the end, grouping
          # by the prefix.
          self.restrict = ar[8]

  def __str__(self):
    st = 'NA'
    if 'str_templates' in self.__dict__:
      st = '*'.join(self.str_templates)
    if self.rank:
      return "<Feat rank=%d n=%d mi=%.4f hx=%.4f sel=%.4g %s>" % (self.rank, self.order, self.ig, self.hx, self.selectivity, st)
    return "<Feat n=%d ig=%.4f hx=%.4f sel=%.4g %s>" % (self.order, self.ig, self.hx, self.selectivity, st)

  def str_like_input(self):
    #y = self.ig / (1 + self.hx * self.hx)
    #y = self.ig / (1 + self.hx)
    y = self.score
    it = '*'.join(map(str, self.int_templates))
    st = 'NA'
    if 'str_templates' in self.__dict__:
      st = '*'.join(self.str_templates)
    return "%f\t%f\t%f\t%f\t%d\t%s\t%s" % \
      (y, self.ig, self.hx, self.selectivity, len(self.int_templates), it, st)

  def features_in_one_str(self):
    return '*'.join(self.str_templates)

  @staticmethod
  def from_file(filename, template_name_bug=False):
    with open(filename, 'r') as f:
      for i, line in enumerate(f):
        yield Feature(line, rank=i+1, template_name_bug=template_name_bug)

def build_feature_set(raw_feature_file, output_ff=None, budget=10, sim_thresh=5.0, show=False):
  ''' budget = how many features to put in the final feature set (more than 100 takes a while) '''
  # Read in the sorted list of features
  # Take a budget of number of features
  # Greedily add to the feature set (they should arrive in decreasing order of IG) unless a similarity to a pre-existing feature is found

  of = None
  if output_ff:
    print 'writing features to', output_ff
    of = open(output_ff, 'w')

  fs = []
  for i, feat in enumerate(Feature.from_file(raw_feature_file, template_name_bug=TEMPLATE_NAME_BUG)):
    if i % 50 == 0:
      sys.stdout.write(" %d(%d)" % (i, len(fs)))
    sim_max = (None, 0.0)
    for f in fs:
      if f.order == feat.order:
        sim = similarity_feature(f.str_templates, feat.str_templates)
        #print sim, f, feat
        if sim > sim_max[1]:
          sim_max = (f, sim)
          if sim_max[1] >= sim_thresh and not show:
            # we've already proved that this feature won't be used
            break
    if sim_max[1] < sim_thresh:
      if show:
        print 'keeping', feat
        print '\tbecause', sim_max[1], sim_max[0]
        if sim_max[0]:
          similarity_feature(sim_max[0].str_templates, feat.str_templates, show=True)
      fs.append(feat)
      of.write(feat.str_like_input() + '\n')
      of.flush()
      if len(fs) >= budget:
        break
    else:
      if show:
        print 'dropping', feat
        print '\tbecause', sim_max[1], sim_max[0]
        if sim_max[0]:
          similarity_feature(sim_max[0].str_templates, feat.str_templates, show=True)
    if show:
      print

  print
  print 'final feature set:\n', '\n'.join(map(str, fs))

  if of:
    of.close()
    
# This has since been fixed in java code, but fixed double-named templates like:
# intpu:  CfgFeat-CommonParent-Rule-CfgFeat-CommonParent-Rule-Top25
# output: CfgFeat-CommonParent-Rule-Top25
BROKEN_TEMPLATE_NAME_ENDING = re.compile('^(.*)-((Top|Cnt)\d+)$')
def undo_template_name_bug(template_name):
  m = BROKEN_TEMPLATE_NAME_ENDING.match(template_name)
  if m:
    tt = m.group(1)
    rest = m.group(2)
    mid = len(tt) / 2
    #print 'template_name=', template_name
    #print 'tt=', tt, 'mid=', mid, 'tt[mid]=', tt[mid], 'len(tt)=', len(tt)
    #print tt[:mid]
    #print tt[mid + 1:]
    assert len(tt) % 2 == 1 and tt[mid] == '-'
    assert tt[:mid] == tt[mid+1:]
    return tt[:mid] + '-' + rest
  else:
    return template_name

class Unbuffered(object):
   def __init__(self, stream):
       self.stream = stream
   def write(self, data):
       self.stream.write(data)
       self.stream.flush()
   def __getattr__(self, attr):
       return getattr(self.stream, attr)

if __name__ == '__main__':
  sys.stdout = Unbuffered(sys.stdout)
  sys.stderr = Unbuffered(sys.stderr)
  if len(sys.argv) != 5:
    print 'please provide:'
    print '1) a similarity threshold, e.g. 5, higher is less strict'
    print '2) an input feature IG file, e.g. scripts/having-a-laugh/ig-feat.txt'
    print '3) a budget size (number of features to output -- more than 100 is slow)'
    print '4) an output file to dump feature set to'
    sys.exit(1)
  #ff = os.path.join(os.path.dirname(os.path.realpath(__file__)), 'ig-feat.txt')
  st = float(sys.argv[1])
  ff = sys.argv[2]
  b = int(sys.argv[3])
  of = sys.argv[4]
  build_feature_set(ff, output_ff=of, budget=b, sim_thresh=st, show=False)







'''
IDEA THAT I WILL PROBABLY NEVER IMPLEMENT:

This similarity shenanigans is annoying.
Sort of want an alignment between templates in a feature.
dist(t1, t2) = min_pi sum_i dist(t1[i], t2[pi[i]])
where
dist(template1, template2) is given in the code i just wrote

we would now write the ILP to have two objectives:
- minimize the feature-feature alignment cost (distance)
- given this, minimize the redundancy cost determined from those alignments

Problem
i indexes products (features)
j indexes templates (within a feature)

cost(i1, j1, i2, j2) = (1 + smooth) / (edit_dist(templates[i1][j1], templates[i2][j2]) + smooth)


# Alignment problem
# (this part is clearly harder: could instantiate (1000*4)^2/2 = 8M variables)
# ... wait a second, this problem clearly decomposes into many alignment problems between 4x4 template feature pairs
min_a sum_{i1,j1,i2,j2} cost(i1,j1,i2,j2) * a_{i1,j1,i2,j2}
s.t. a_{i1,j1,i2,j2} in [0,1], relaxed from {0,1}
     sum_{i1,j1} a_{i1,j1,i2,j2} = 1  for all i2,j2
     sum_{i2,j2} a_{i1,j1,i2,j2} = 1  for all i1,j1


# Duplication penalty
cost(i1,i2) += sum_{j1,j2} cost(i1,j1,i2,j2) * a_{i1,j1,i2,j2}
cost(i1,i2) += lambda_2   # L2 regularization (on templates)
cost(i) += lambda_1       # L1 regularization (on templates)
cost(i) -= information_gain(i)

min_t  t(*)' * cost(*,*) * t(*)
s.t. t(i) in [0,1], relaxed from {0,1}


'''







