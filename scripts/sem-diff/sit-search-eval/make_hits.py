#!/usr/bin/env python

import os, sys, random, codecs, math, csv
from operator import itemgetter
import concrete, gzip
from thrift.protocol import TCompactProtocol
from thrift.transport import TTransport

# Given:
# 1) a directory contain <SF_id>.txt
# 2) a /sit-search/trigger-id-hit/<SF_id>/related-ent* where we take the first line
# 3) an output dir

# I can use different CSS to change the color of various types of <span class="foo"> by class


import cStringIO

class UnicodeCsvWriter:
  """
  A CSV writer which will write rows to CSV file "f",
  which is encoded in the given encoding.
  """

  def __init__(self, f, dialect=csv.excel, encoding="utf-8", **kwds):
    # Redirect output to a queue
    self.queue = cStringIO.StringIO()
    self.writer = csv.writer(self.queue, dialect=dialect, **kwds)
    self.stream = f
    self.encoder = codecs.getincrementalencoder(encoding)()

  def writerow(self, row):
    self.writer.writerow([s.encode("utf-8") for s in row])
    # Fetch UTF-8 output from the queue ...
    data = self.queue.getvalue()
    data = data.decode("utf-8")
    # ... and reencode it into the target encoding
    data = self.encoder.encode(data)
    # write to the target stream
    self.stream.write(data)
    # empty queue
    self.queue.truncate(0)

  def writerows(self, rows):
    for row in rows:
      self.writerow(row)


class EntLinkEval:
  ''' DEPRECATED '''
  def __init__(self, f_explanation, f_query, f_faker, flip, coref):
    assert os.path.isfile(f_explanation)
    assert os.path.isfile(f_query)
    assert os.path.isfile(f_faker)
    self.f_explanation = f_explanation
    self.f_query = f_query
    self.f_faker = f_faker
    self.flip = flip
    self.coref = coref

  def valid(self):
    return self.explanation() is not None

  def explanation(self):
    #print 'reading from ' + self.f_explanation
    lines = codecs.open(self.f_explanation, 'r', 'utf-8').readlines()
    if lines:
      return lines[0].rstrip()
    return None

  def query_or_faker(self, flip):
    f = self.f_faker if flip else self.f_query
    lines = codecs.open(f, 'r', 'utf-8').readlines()
    if lines:
      return lines[0].rstrip()
    return None

  def query(self):
    return self.query_or_faker(self.flip)

  def faker(self):
    return self.query_or_faker(not self.flip)

  def write_html(self, dest):
    print 'writing to ' + dest
    with codecs.open(dest, 'w', 'utf-8') as f:
      f.write('<html><body>\n')
      f.write('flip: ' + str(self.flip) + '\n')
      f.write('query:\n' + self.query() + '\n')
      f.write('faker:\n' + self.faker() + '\n')
      f.write('explanation:\n' + self.explanation() + '\n')
      f.write('</body></html>\n')

class Queries:
  ''' mapping between SF query id and comm/tok + HTML mention '''
  def __init__(self, root):
    self.id2query = {}
    self.id2commTokHeadSpan = {}  # values are (commId, tokUuid)
    for f in os.listdir(root):
      ff = os.path.join(root, f)
      commTokLocs, mention = codecs.open(ff, 'r', 'utf-8').readlines()
      i = f.replace('.txt', '')
      self.id2query[i] = mention.rstrip()
      #comm, tok, head, span = commTokLocs.rstrip().split('\t')
      self.id2commTokHeadSpan[i] = commTokLocs.rstrip().split('\t')
    self.rand = random.Random()
    self.rand.seed(9001)

  def mention(self, q_id):
    return self.id2query[q_id]

  def faker(self, exclude):
    ids = self.id2query.keys()
    while True:
      i = self.rand.choice(ids)
      if i != exclude:
        #return self.id2query[i]
        return i

class Explanation:
  def __init__(self, rank, q_id, key, meta_line, mention_line):
    self.rank = rank
    self.q_id = q_id
    self.key = key
    self.meta = meta_line.rstrip().split('\t')
    self.mention = mention_line.rstrip()
    self.link_score = float(self.meta[4])
    self.trigger_score = float(self.meta[2])
    self.trigger = self.meta[1]
    self.comm_id = self.meta[5]
    self.tok_uuid = self.meta[6]

  def write_html(self, parent_dir, query, faker):
    ff = os.path.join(parent_dir, 'coref-' + self.key + '.html')
    #print ff
    with codecs.open(ff, 'w', 'utf-8') as f:
      f.write('query:\n' + query + '\n')
      f.write('\n')
      f.write('faker:\n' + faker + '\n')
      f.write('\n')
      f.write('mention:\n' + self.mention + '\n')
      f.write('\n')
    return ff

class ArgMax:
  def __init__(self):
    self.best = None
    self.best_score = None

  def add(self, item, score):
    if self.best is None or score > self.best_score:
      self.best = item
      self.best_score = score;

class DocStore:
  def __init__(self, root):
    # data/sit-search/fetch-comms-cache/
    self.root = root
    self.comm_cache = {}

  def get_comm(self, comm_id):
    try:
      return self.comm_cache[comm_id]
    except:
      c = self.read_comm(comm_id)
      if len(self.comm_cache) > 100:
        self.comm_cache = {}    # half-assed LRU
      self.comm_cache[comm_id] = c
      return c

  def read_comm(self, comm_id):
    f = os.path.join(self.root, comm_id + '.comm.gz')
    bs = gzip.open(f, 'rb').read()
    t = TTransport.TMemoryBuffer(bs)
    p = TCompactProtocol.TCompactProtocol(t)
    c = concrete.Communication()
    c.read(p)
    return c

  def get_tok_context(self, comm_id, tok_uuid, before=1, after=1):
    c = self.get_comm(comm_id)
    toks = []
    idx = -1
    for sect in c.sectionList:
      for sent in sect.sentenceList:
        if sent.tokenization.uuid.uuidString == tok_uuid:
          assert idx < 0
          idx = len(toks)
        toks.append(sent.tokenization)
    assert idx >= 0
    s = max(0, idx - before)
    e = min(len(toks), idx + after + 1)
    return toks[s:e]

  def get_tok_from(self, comm_id, tok_uuid, offset):
    c = self.get_comm(comm_id)
    toks = []
    idx = -1
    for sect in c.sectionList:
      for sent in sect.sentenceList:
        if sent.tokenization.uuid.uuidString == tok_uuid:
          assert idx < 0
          idx = len(toks)
        toks.append(sent.tokenization)
    assert idx >= 0
    idx += offset
    assert idx >= 0
    assert idx < len(toks)
    return toks[idx]


def harmonic_mean(a, b):
  return math.sqrt(a) * math.sqrt(b)

def tokens(tokenization):
  toks = []
  for t in tokenization.tokenList.tokenList:
    toks.append(t.text)
  return toks

def context(ds, meat, comm_id, tok_uuid, before=1, after=1):
  assert before >= 0
  assert after >= 0
  lines = []
  for i in range(-before, after+1):
    if i == 0:
      lines.append(meat)
    else:
      try:
        #c = u' '.join(tokens(ds.get_tok_from(comm_id, tok_uuid, i))).encode('utf-8')
        c = u' '.join(tokens(ds.get_tok_from(comm_id, tok_uuid, i)))
      except:
        #print 'fail', comm_id, tok_uuid, i
        c = u''
      lines.append(c)
  return lines

prompts = {
  'coref': 'Is the <span class="arg0">green entity</span> the same as either <span class="query">blue entity</span>?',
  'related': 'Is the <span class="arg1">green entity</span> more related to the first or second <span class="query">blue entity</span>?',
  'trigger': 'Is the situation relating <span class="arg0">the orange entities</span> described by<br/> a <span class="blue">blue</span> word, a <span class="green">green</span> word, or neither?',
}

def coref_and_related_write_html(mode, rand, parent_dir, key, mention, query, faker):
  ''' key should be a string containing the SF query id and a related entity id
      mention, query, and faker should be text blocks with <span>'s in them
  '''
  assert mode in ['coref', 'related']
  ff = os.path.join(parent_dir, mode + '-individual')
  if not os.path.isdir(ff):
    os.makedirs(ff)
  ff = os.path.join(ff, key + '.html')
  flip = rand.randint(0,1) == 0
  if flip:
    query, faker = faker, query
  a = []
  a.append('<!-- flip=' + str(flip) + ' -->')
  a.append('<style type=\"text/css\">')
  a.append('span.query { color: blue; font-weight: bold; }')
  if mode == 'coref':
    a.append('span.arg0 { color: green; font-weight: bold; }')
  elif mode == 'related':
    a.append('span.arg1 { color: green; font-weight: bold; }')
  else:
    raise Exception('mode=' + str(mode))
  a.append('</style>')
  a.append('<center>')
  a.append('<table border="1" cellpadding="10" width="80%">')
  a.append('<tr><td>' + mention.replace('\n', ' ') + '</td></tr>')
  a.append('<tr><td>' + query.replace('\n', ' ') + '</td></tr>')
  a.append('<tr><td>' + faker.replace('\n', ' ') + '</td></tr>')
  a.append('</table>')
  #a.append('<h3>' + prompts[mode] + '</h3>')
  a.append('</center>')
  with codecs.open(ff, 'w', 'utf-8') as f:
    for line in a:
      f.write(line)
      f.write('\n')
  return ff, flip, ' '.join(a)


def trigger_write_html(rand, parent_dir, key, mention):
  ''' writes out arg0, arg1, trigger, fakeTrigger '''
  ff = os.path.join(parent_dir, 'trigger-individual')
  if not os.path.isdir(ff):
    os.makedirs(ff)
  ff = os.path.join(ff, key + '.html')
  flip = rand.randint(0,1) == 0
  a = []
  a.append('<!-- flip=' + str(flip) + ' -->')
  a.append('<style type=\"text/css\">')
  a.append('span.blue { color: blue; font-weight: bold; }')
  a.append('span.green { color: green; font-weight: bold; }')
  a.append('span.arg0 { color: orange; font-weight: bold; }')
  a.append('span.arg1 { color: orange; font-weight: bold; }')
  if flip:
    a.append('span.trigger { color: blue; font-weight: bold; }')
    a.append('span.faker { color: green; font-weight: bold; }')
  else:
    a.append('span.trigger { color: green; font-weight: bold; }')
    a.append('span.faker { color: blue; font-weight: bold; }')
  a.append('</style>')
  a.append('<center>')
  a.append('<table border="1" cellpadding="10" width="80%">')
  a.append('<tr><td>' + mention.replace('\n', ' ') + '</td></tr>')
  a.append('</table>')
  #a.append('<h3>' + prompts['trigger'] + '</h3>')
  a.append('</center>')
  with codecs.open(ff, 'w', 'utf-8') as f:
    for line in a:
      f.write(line)
      f.write('\n')
  return ff, flip, ' '.join(a)
  


if __name__ == '__main__':
  #ds = DocStore('data/sit-search/fetch-comms-cache/')
  ds = DocStore('../fetch-comms-cache/')
  #c = ds.get_comm('10,000_Black_Men_Named_George')
  #print c.text
  #toks = ds.get_tok_context('Robert_Paxton', '626a11a9-2bc6-a1cf-f757-0000505de41d')
  #print len(toks)
  #print ' '.join([t.text for t in toks[1].tokenList.tokenList])

  d_queries = 'data/sit-search/query-mentions'
  d_explanations = 'data/sit-search/trigger-id-hit'
  d_output = 'data/sit-search/hit-output'

  #qs = []
  #for q in os.listdir(d_queries):
  #  qs.append(os.path.join(d_queries, q))
  #print qs

  qs = Queries('data/sit-search/query-mentions')
  #print qs.id2query

  rand = random.Random()
  rand.seed(9001)

  d_csv = os.path.join(d_output, 'csv')
  if not os.path.isdir(d_csv):
    os.makedirs(d_csv)
  csv_coref = UnicodeCsvWriter(open(os.path.join(d_csv, 'coref.csv'), 'wb'))
  csv_related = UnicodeCsvWriter(open(os.path.join(d_csv, 'related.csv'), 'wb'))
  csv_trigger = UnicodeCsvWriter(open(os.path.join(d_csv, 'trigger.csv'), 'wb'))

  csv_coref.writerow(['sfId', 'key', 'flip', 'linkScore', 'relatedScore', 'mentionHtml'])
  csv_related.writerow(['sfId', 'key', 'flip', 'linkScore', 'relatedScore', 'mentionHtml'])
  csv_trigger.writerow(['sfId', 'key', 'flip', 'linkScore', 'relatedScore', 'mentionHtml'])

  for q_id in os.listdir(d_explanations):

    # DEBUGGING
    #if q_id != 'SF13_ENG_053':
    #  continue

    d_mentions = os.path.join(d_explanations, q_id)
    query = os.path.join(d_queries, q_id + '.txt')
    es = []
    for ex in os.listdir(d_mentions):
      if not ex.startswith('related-'):
        continue
      related = os.path.join(d_mentions, ex)
      meta = os.path.join(d_mentions, ex.replace('related-', 'meta-'))
      key = q_id + '_' + ex.replace('related-', '').replace('.txt', '')

      # Select the explanation with the highest trigger score
      a = ArgMax()
      for idx, (lr, lm) in enumerate(zip(codecs.open(related, 'r', 'utf-8'), codecs.open(meta, 'r', 'utf-8'))):
        e = Explanation(idx, q_id, key, lm, lr)
        a.add(e, e.trigger_score)
      if a.best:
        #ts = harmonic_mean(a.best.trigger_score, math.sqrt(a.best.link_score))
        #ts = a.best.trigger_score * a.best.link_score
        #ts = a.best.trigger_score
        #ts = a.best.link_score
        ts = a.best.trigger_score + a.best.link_score
        es.append( (a.best, ts) )

    es.sort(key=itemgetter(1), reverse=True)
    k = 15
    if len(es) > k:
      es = es[:k]
    for e, s in es:
      print 'working on trigger=' + e.trigger \
          + ' trigger_score=' + str(e.trigger_score) \
          + ' link_score=' + str(e.link_score) \
          + ' score=' + str(s)
      f_id = qs.faker(e.q_id)
      f_comm, f_tok, f_head, f_span = qs.id2commTokHeadSpan[f_id]
      q_comm, q_tok, q_head, q_span = qs.id2commTokHeadSpan[e.q_id]

      q_cur = qs.id2query[e.q_id].encode('utf-8')
      query = u'\n'.join(context(ds, q_cur, q_comm, q_tok))

      f_cur = qs.id2query[f_id].encode('utf-8')
      faker = u'\n'.join(context(ds, f_cur, f_comm, f_tok))

      m_cur = e.mention
      mention = u'\n'.join(context(ds, m_cur, e.comm_id, e.tok_uuid))

      # COREF
      f_coref, flip_coref, c_coref = coref_and_related_write_html('coref', rand, d_output, e.key, mention, query, faker)
      print s, e.link_score, e.rank, e.key, f_coref
      assert '\n' not in c_coref
      try:
        csv_coref.writerow([e.q_id, e.key, str(flip_coref), str(e.link_score), str(s), unicode(c_coref)])
      except:
        print 'err on related + ' + e.key

      # RELATED
      f_related, flip_related, c_related = coref_and_related_write_html('related', rand, d_output, e.key, mention, query, faker)
      print s, e.link_score, e.rank, e.key, f_related
      assert '\n' not in c_related
      try:
        csv_related.writerow([e.q_id, e.key, str(flip_related), str(e.link_score), str(s), unicode(c_related)])
      except:
        print 'err on related + ' + e.key

      # TRIGGER
      f_trigger, flip_trigger, c_trigger = trigger_write_html(rand, d_output, e.key, mention)
      print s, e.link_score, e.rank, e.key, f_trigger
      assert '\n' not in c_trigger
      try:
        csv_trigger.writerow([e.q_id, e.key, str(flip_trigger), str(e.link_score), str(s), unicode(c_trigger)])
      except:
        print 'err on trigger + ' + e.key




