
'''
  The point of this script is to do error analysis on what the good ngrams are
  which constitute signal in the ngrams concept model which isn't named entity signal.

  Y = a entity (with various system-summaries) where rank(ngram) > rank(entities)
  X_i = some ngram binary feature

  Read through the rows and extract ngram features from the summaries themselves

  I would like to have backoff features from the ngrams.
  Could use POS bigrams, but that requires additional pipes.
  Can use just single words as features.
  Is there an easy way to get the same features used by the extraction?
  In the feature files there is a location feature, perhaps just put that in the summary?

  I have passed through the features, but for which concepts?
  The feature extraction code only applies to slots, not entities or ngrams.
  Crap, we already have the ngram concepts which via <!-- concepts: <ngrams> -->
'''
# "HITId","HITTypeId","Title","Description","Keywords","Reward","CreationTime","MaxAssignments","RequesterAnnotation","AssignmentDurationInSeconds","AutoApprovalDelayInSeconds","Expiration","NumberOfSimilarHITs","LifetimeInSeconds","AssignmentId","WorkerId","AssignmentStatus","AcceptTime","SubmitTime","AutoApprovalTime","ApprovalTime","RejectionTime","RequesterFeedback","WorkTimeInSeconds","LifetimeApprovalRate","Last30DaysApprovalRate","Last7DaysApprovalRate","Input.subj","Input.tag","Input.entityName","Input.sum1sys","Input.sum2sys","Input.sum3sys","Input.nasys","Input.sum1text","Input.sum2text","Input.sum3text","Answer.sum1rank","Answer.sum2rank","Answer.sum3rank","Approve","Reject"


from trueskill_eval import Row
import codecs, sys, csv, re

def ngrams(seq, n):
  for i in range(len(seq)-(n-1)):
    yield seq[i:i+n]

def extract_concepts(t):
  for chunk in re.findall('<!-- concepts: (.*?) -->', t):
    for concept in chunk.split():
      yield concept

if __name__ == '__main__':
  with open('Batch_2754096_batch_results.csv', 'rb') as csvfile:
    reader = csv.DictReader(csvfile)
    for row in reader:
      row = Row(row)
      if row.ngrams_beat_entities():

        ### This is how you get the concepts from jser
        t = list(extract_concepts(row.get_summary_text_for_sys('w')))
        print '\n'.join(t)

        ### This is how you extract bigrams manually
        #t = row.get_summary_text_for_sys('e')
        #t = re.sub('<[^>]+>', '', t)
        #t = [x for x in t.split() if len(x) >= 2]
        #t = list(ngrams(t, 2))
        #print t


