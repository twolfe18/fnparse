
import sys

n = int(sys.argv[1])

print 'def span-w1 <span> <tokenIndex> <tokenIndex>'
for i in range(n):
  print "schema span-w1 %d-%d %d %d" % (i,i+1,i,i+1)

print 'def span <span> <tokenIndex> <tokenIndex>'
for i in range(n):
  for j in range(i, n):
    print "schema span %d-%d %d %d" % (i,j,i,j)

