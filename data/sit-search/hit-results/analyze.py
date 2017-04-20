#!/usr/bin/env python

import csv, sys, collections
import pandas as pd

'''
==> coref-Batch_2674241_batch_results.csv <==
"HITId"
"HITTypeId"
"Title"
"Description"
"Keywords"
"Reward"
"CreationTime"
"MaxAssignments"
"RequesterAnnotation"
"AssignmentDurationInSeconds"
"AutoApprovalDelayInSeconds"
"Expiration"
"NumberOfSimilarHITs"
"LifetimeInSeconds"
"AssignmentId"
"WorkerId"
"AssignmentStatus"
"AcceptTime"
"SubmitTime"
"AutoApprovalTime"
"ApprovalTime"
"RejectionTime"
"RequesterFeedback"
"WorkTimeInSeconds"
"LifetimeApprovalRate"
"Last30DaysApprovalRate"
"Last7DaysApprovalRate"
"Input.sfId"
"Input.key"
"Input.flip"
"Input.linkScore"
"Input.relatedScore"
"Input.mentionHtml"
"Answer.Q1Answer"
"Approve"
"Reject"

==> related-Batch_2674254_batch_results.csv <==
"HITId","HITTypeId","Title","Description","Keywords","Reward","CreationTime","MaxAssignments","RequesterAnnotation","AssignmentDurationInSeconds","AutoApprovalDelayInSeconds","Expiration","NumberOfSimilarHITs","LifetimeInSeconds","AssignmentId","WorkerId","AssignmentStatus","AcceptTime","SubmitTime","AutoApprovalTime","ApprovalTime","RejectionTime","RequesterFeedback","WorkTimeInSeconds","LifetimeApprovalRate","Last30DaysApprovalRate","Last7DaysApprovalRate","Input.sfId","Input.key","Input.flip","Input.linkScore","Input.relatedScore","Input.mentionHtml","Answer.Q1Answer","Approve","Reject"

==> trigger-Batch_2674274_batch_results.csv <==
"HITId","HITTypeId","Title","Description","Keywords","Reward","CreationTime","MaxAssignments","RequesterAnnotation","AssignmentDurationInSeconds","AutoApprovalDelayInSeconds","Expiration","NumberOfSimilarHITs","LifetimeInSeconds","AssignmentId","WorkerId","AssignmentStatus","AcceptTime","SubmitTime","AutoApprovalTime","ApprovalTime","RejectionTime","RequesterFeedback","WorkTimeInSeconds","LifetimeApprovalRate","Last30DaysApprovalRate","Last7DaysApprovalRate","Input.sfId","Input.key","Input.flip","Input.linkScore","Input.relatedScore","Input.mentionHtml","Answer.Q1Answer","Approve","Reject"
'''

class Row:
  def __init__(self, dict_reader_row):
    self.dict_reader_row = dict_reader_row
    for k, v in dict_reader_row.iteritems():
      self.__dict__[k.replace('.', '_')] = v

  @property
  def hit(self):
    return self.Input_hitId

  @property
  def score(self):
    return float(self.Input_score)

  @property
  def label(self):
    return self.Answer_Q1Answer

  @property
  def flip(self):
    return self.Input_flip == 'True'

  def correct_coref(self):
    assert self.Answer_Q1Answer in ['first', 'second', 'neither']
    return (self.Answer_Q1Answer == 'first' and not self.flip) \
      or (self.Answer_Q1Answer == 'second' and self.flip)

  def correct_related(self):
    assert self.Answer_Q1Answer in ['first', 'second']
    return (self.Answer_Q1Answer == 'first' and not self.flip) \
      or (self.Answer_Q1Answer == 'second' and self.flip)

  def is_per_query(self):
    sf, lang, qid = self.Input_sfId.split('_')
    assert sf == 'SF13' and lang == 'ENG'
    return int(qid) <= 50

  def is_org_query(self):
    sf, lang, qid = self.Input_sfId.split('_')
    assert sf == 'SF13' and lang == 'ENG'
    assert int(qid) <= 100
    return 50 < int(qid) <= 100



#if len(sys.argv) != 2:
#  print 'please provide a mturk batch results csv file'
#  sys.exit(1)
#
#rows = []
#turkers = set()
#with open(sys.argv[1]) as csvfile:
#  reader = csv.DictReader(csvfile)
#  for r in reader:
#    r = Row(r)
#    rows.append(r)
#    turkers.add(r.WorkerId)
#
#    print r.Answer_Q1Answer, r.Input_flip, r.Input_key

from collections import Counter
from collections import defaultdict

def all_lines(f):
  l = []
  with open(f) as csvfile:
    reader = csv.DictReader(csvfile)
    for line in reader:
      l.append(Row(line))
  return l

def group_by(items, key_func):
  k2l = defaultdict(list)
  for i in items:
    k = key_func(i)
    k2l[k].append(i)
  return k2l

coref = all_lines('feb4/coref-Batch_2676002_batch_results.csv')
related = all_lines('feb4/related-Batch_2676004_batch_results.csv')
trigger = all_lines('feb4/trigger-Batch_2675999_batch_results.csv')


bad = filter(lambda r: r.Answer_Q1Answer == 'neither', coref)
bad_annotators = set([r.WorkerId for r in bad])
bad_keys = set([r.Input_key for r in bad])
print 'coref neither instances', len(bad)
print 'coref neither annotators', len(bad_annotators)
print 'coref neither keys', len(bad_keys)
def keep_coref_or_related(r):
  return r.WorkerId in bad_annotators or r.Input_key in bad_keys
coref = filter(keep_coref_or_related, coref)


def trigger_label(answer, flip):
  assert flip in ['True', 'False']
  assert answer in ['neither', 'green', 'blue']
  if answer == 'neither':
    return 'neither'
  if (answer == 'blue' and flip == 'True') or (answer == 'green' and flip == 'False'):
    return 'system'
  return 'intruder'

def trigger_label_row(row):
  return trigger_label(row.Answer_Q1Answer, row.Input_flip)

def cnt2nice(counter):
  t = []
  z = sum(counter.values())
  for lab, count in counter.iteritems():
    t.append("%s %d %.1f%%" % (lab, count, (100.0*count)/z))
  return '[' + ', '.join(t) + ']'

print 'trigger ALL', cnt2nice(Counter([trigger_label_row(r) for r in trigger]))
print 'trigger PER', cnt2nice(Counter([trigger_label_row(r) for r in trigger if r.is_per_query()]))
print 'trigger ORG', cnt2nice(Counter([trigger_label_row(r) for r in trigger if r.is_org_query()]))

def prec_coref(rows):
  n, z = 0, 0
  for r in rows:
    if r.correct_coref():
      n += 1
    z += 1
  return float(n) / z

def prec_related(rows):
  n, z = 0, 0
  for r in rows:
    if r.correct_related():
      n += 1
    z += 1
  return float(n) / z

def prec_lamb(rows, lam):
  n, z = 0, 0
  for r in rows:
    if lam(r):
      n += 1
    z += 1
  return float(n) / z

def avg(items):
  return sum(items) / float(len(items))


print 'coref ALL acc', prec_coref(coref)
print 'coref PER acc', prec_coref(r for r in coref if r.is_per_query())
print 'coref ORG acc', prec_coref(r for r in coref if r.is_org_query())
print 'related ALL acc', prec_related(related)
print 'related PER acc', prec_related(filter(lambda r: r.is_per_query(), related))
print 'related ORG acc', prec_related(filter(lambda r: r.is_org_query(), related))


gb_coref = group_by(coref, lambda row: row.Input_key)
gb_related = group_by(related, lambda row: row.Input_key)

n, z = 0, 0
n_per, z_per = 0, 0
n_org, z_org = 0, 0
for key in gb_coref.keys():
  if gb_coref[key] and gb_related[key]:
    c = [x.correct_coref() for x in gb_coref[key]]
    r = [x.correct_related() for x in gb_related[key]]
    n += avg(c) * avg(r)
    z += 1
    if gb_coref[key][0].is_per_query():
      n_per += avg(c) * avg(r)
      z_per += 1
    else:
      n_org += avg(c) * avg(r)
      z_org += 1
print 'coref&related ALL acc', n/z
print 'coref&related PER acc', n_per/z_per
print 'coref&related ORG acc', n_org/z_org



for r in coref:
  if not r.correct_coref():
    print 'coref mistake:', r.Input_mentionHtml
for r in related:
  if not r.correct_related():
    print 'related mistake:', r.Input_mentionHtml



### print '### What is the yes/no distribution for each turker?'
### wac = collections.defaultdict(lambda: collections.defaultdict(int))
### for r in rows:
###   wac[r.WorkerId][r.label] += 1
### for worker, counts in wac.iteritems():
###   print worker, counts.items()
### print
### 
### 
### print '### Does score correlate with coref/noref?'
### s_coref = []
### s_noref = []
### for r in rows:
###   if r.label == 'coref':
###     s_coref.append(r.score)
###   else:
###     s_noref.append(r.score)
### #print 'Coref:'
### #print pd.Series(s_coref).describe()
### #print 'Noref:'
### #print pd.Series(s_noref).describe()
### c = ['Coref ############'] + str(pd.Series(s_coref).describe()).split('\n')
### n = ['Noref ############'] + str(pd.Series(s_noref).describe()).split('\n')
### print '\n'.join(x + '\t' + y for x,y in zip(c,n))
### print
### 
### 
### print '### Possible outliers:'
### scores = pd.Series(s_coref + s_noref)
### mu = scores.mean()
### sd = scores.std()
### for r in rows:
###   z = (r.score - mu) / sd
###   if z > 1 and r.label == 'noref':
###     print z, r.hit, r.WorkerId, r.score, r.label
###   elif z < -1 and r.label == 'coref':
###     print z, r.hit, r.WorkerId, r.score, r.label







