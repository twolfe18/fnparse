
function abs(x) {
	return ((x < 0.0) ? -x : x)
}

BEGIN {
	sAbs = 0
	sSq = 0
	mx = 0
	mxLine = ""
	mn = 0
	mnLine = ""
	nnz = 0
	n = 0
}

{
	a = abs($1)
	sAbs += a
	sSq += $1 * $1
	if(a > 0.0001)
		nnz += 1
	if($1 > mx) {
		mx = a
		mxLine = $0
	}
	if($1 < mn) {
		mn = $1
		mnLine = $0
	}
	n += 1
}

END {
	print "L1 norm ", sAbs
	print "L2 norm ", sqrt(sSq)
	print "biggest weight", mxLine
	print "smallest weight", mnLine
	print "num non-zero ", nnz
	print "dimension ", n
}

