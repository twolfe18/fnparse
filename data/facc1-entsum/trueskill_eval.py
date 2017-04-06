
import sys, collections, random, itertools
import csv
import trueskill

class Row:
  def __init__(self, dict_reader_row):
    self.dict_reader_row = dict_reader_row
    for k, v in dict_reader_row.iteritems():
      if k.startswith('Input.'):
        k = k[len('Input.'):]
      if k.startswith('Answer.'):
        k = k[len('Answer.'):]
      self.__dict__[k.replace('.', '_')] = v 

    #self.sum1rank = 'Middle'
    #self.sum2rank = 'Best'
    #self.sum3rank = 'Worst'

  @property
  def hit(self):
    return self.Input_hitId

  @property
  def tag2(self):
    return (self.tag[0], int(self.tag[1:]))

  def zip_sys_rank(self):
    r = desc2rank(self.sum1rank)
    for sys in self.sum1sys.split():
      yield (sys, r)
    r = desc2rank(self.sum2rank)
    for sys in self.sum2sys.split():
      yield (sys, r)
    r = desc2rank(self.sum3rank)
    for sys in self.sum3sys.split():
      yield (sys, r)

def desc2rank(desc):
  if desc == 'Best':
    return 0
  if desc == 'Middle':
    return 1
  if desc == 'Worst':
    return 2
  raise Exception('unknown: ' + desc)

def instances(filename):
  with open(filename, 'rb') as csvfile:
    #reader = csv.reader(csvfile)
    reader = csv.DictReader(csvfile)
    for row in reader:
      #print row
      #subj, tag, entity, s1sys, s2sys, s3sys, nasys, s1text, s2text, s3text = row
      #print subj, tag, entity
      yield Row(row)

if len(sys.argv) != 2:
  print 'please provide a hit results csv'
  sys.exit(1)
source = sys.argv[1]
#source = 'code-testing-data/summaries/hit-unlab-dev.sample40.csv'
inst = list(instances(source))
#keyfunc = lambda r: r.tag
keyfunc = lambda r: r.tag2
inst = sorted(inst, key=keyfunc)
for tag, cur_inst in itertools.groupby(inst, key=keyfunc):
  print 'working on tag', tag

  env = trueskill.TrueSkill()
  rmemo = collections.defaultdict(env.create_rating)

  cur_inst = list(cur_inst)
  for r in cur_inst:
    #print r.subj, r.tag, r.entityName

    # This is wrong!
    # If I make [(es,e), (s), (w)],
    # this is intpretted as "es an e are on the same team",
    # which is not true, they just have the same summary/results.
    # They should all be on separate teams and receieve ties (same ranks).
    ### ratings = [
    ###   r.sum1ratings(rmemo),
    ###   r.sum2ratings(rmemo),
    ###   r.sum3ratings(rmemo),
    ### ]
    ### rank = [
    ###   desc2rank(r.sum1rank),
    ###   desc2rank(r.sum2rank),
    ###   desc2rank(r.sum3rank),
    ### ]
    ### new_ratings = env.rate(ratings, ranks=rank)
    ### print new_ratings
    ### for new_group, old_group in zip(new_ratings, ratings):
    ###   for new_rat, old_rat in zip(new_group, old_group):
    ###     new_rat.sys = old_rat.sys
    ###     rmemo[new_rat.sys] = new_rat

    sys_ranks = list(r.zip_sys_rank())
    sys, ranks = zip(*sys_ranks)
    rat = [(rmemo[s],) for s in sys]
    new_rat = env.rate(rat, ranks=ranks)
    for r, s in zip(new_rat, sys):
      rmemo[s] = r[0]

  print 'based on', len(cur_inst), 'instances:'
  sys = sorted(rmemo.keys(), key=lambda s: rmemo[s].mu, reverse=True)
  for s in sys:
    print "%-12s %s" % (s, rmemo[s])
  print


