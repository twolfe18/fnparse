
# give as input (stdin) a hprof file

BEGIN {
	bsearchPercent = 0.0
	#printing = 0
}

#printing {
#	print $6, $2
#}
#$1 == "rank" && $2 == "self" && $3 == "accum" {
#	printing = 1
#}

match($6, "java.util.Arrays.binarySearch.*") {
	bsearchPercent += $2
}

END {
	print "binary search: ", bsearchPercent
}

