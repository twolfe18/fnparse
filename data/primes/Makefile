
primes1.txt:
	wget https://primes.utm.edu/lists/small/millions/primes1.zip --no-check-certificate
	unzip primes1.zip

primes1.byLine.txt.gz: primes1.txt
	cat primes1.txt | tail -n+3 | perl -pe 's/\s*(\d+)\s+/\1\n/g' | gzip -c >primes1.byLine.txt.gz

