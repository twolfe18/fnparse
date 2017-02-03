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
    return (self.Answer_Q1Answer == 'first' and not self.flip) \
      or (self.Answer_Q1Answer == 'second' and self.flip)

  def correct_related(self):
    return (self.Answer_Q1Answer == 'first' and not self.flip) \
      or (self.Answer_Q1Answer == 'second' and self.flip)


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

coref = all_lines('coref-Batch_2674241_batch_results.csv')
related = all_lines('related-Batch_2674254_batch_results.csv')
trigger = all_lines('trigger-Batch_2674274_batch_results.csv')

print 'coref', Counter([(r.Answer_Q1Answer, r.Input_flip) for r in coref])
print 'related', Counter([(r.Answer_Q1Answer, r.Input_flip) for r in related])
print 'trigger', Counter([(r.Answer_Q1Answer, r.Input_flip) for r in trigger])

gb_coref = group_by(coref, lambda row: row.Input_key)
gb_related = group_by(related, lambda row: row.Input_key)

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

def avg(items):
  return sum(items) / float(len(items))

print 'coref micro acc', prec_coref(coref)
#print 'coref macro acc', avg(map(prec_coref, gb_coref.values()))
print 'related micro acc', prec_related(related)
#print 'related macro acc', avg(map(prec_related, gb_related.values()))

n, z = 0, 0
for key in gb_coref.keys():
  if gb_coref[key] and gb_related[key]:
    #print key, gb_coref[key], gb_related[key]
    c = [x.correct_coref() for x in gb_coref[key]]
    r = [x.correct_related() for x in gb_related[key]]
    n += avg(c) * avg(r)
    z += 1
    #print key, avg(c), avg(r), avg(c) * avg(r)
    #print key, max(c), max(r), max(c) and max(r)
print 'coref&related micro acc', n/z






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







