#!/usr/bin/env perl
# Public-repo hygiene sweep (rules: CLAUDE.md "Public-repo hygiene"); this file holds the canonical patterns.
#   perl hygiene-sweep.pl <base>    sweep the lines HEAD adds over <base> (git diff <base>...HEAD)
#   perl hygiene-sweep.pl -         sweep stdin, e.g. gh pr view --json title,body -q '.title + "\n" + .body'
# Prints file:line: [rule] text for every hit. Exit 0 = clean, 1 = hits to judge, 2 = error.
use strict;
use warnings;
$SIG{__DIE__} = sub { print STDERR $_[0]; exit 2 };

my @LINE = (
    # Catches pre-prod, dial-adas-dev, adas.dev.example.com; $SPARE removes the known harmless tokens first.
    [env      => qr/(?<![\/\w])(?:[Dd]ev|DEV|UAT|[Pp]rod|PROD)(?![A-Za-z0-9])|DevOps/],
    [env      => qr/reference[- ]deployment|\bin production\b/i],
    [ticket   => qr/\bMR ?!\d+|(?<![\w\]!])!\d{2,}\b|\b(?!(?:UTF|ISO|SHA|RFC|UUID|HTTP|TLS|CVE|CWE|AES|RSA|HMAC|ECDSA|PBKDF)-)[A-Z]{2,}-\d{2,}\b/],
    [branch   => qr/\b(?:origin|upstream|github|gitlab)\/(?:development|main|master)\b|renumbered at merge/],
    [people   => qr/\bthe user (?:decided|asked|chose|wanted)\b/i],
    # Unquoted dates; quoted ones are fixtures, `2026-11-03-name` is an archive folder.
    [date     => qr/(?<!["'`\d-])20\d\d-[01]\d-[0-3]\d(?!-[a-z]|\d)|\b\d{1,2}:\d{2}(?::\d{2})? ?(?:UTC|CET|CEST|EET|EEST)\b/i],
    [machine  => qr{/Users/\w|/home/\w|\b[A-Za-z]:\\Users\\|~/\.\w|\.rd/docker\.sock}],
    [untracked => qr/\.local\.md\b/],
);
# Phrases checked again across line breaks, after comment prefixes are stripped.
my @WRAP = (
    [env    => qr/reference[-\s]+deployment|\bin\s+production\b/i],
    [people => qr/\bthe\s+user\s+(?:decided|asked|chose|wanted)\b/i],
    [branch => qr/renumbered\s+at\s+merge/],
);
# Missing a real name is worse than a hit to dismiss, so package names like libssl-dev are not spared.
my $SPARE = qr/\/dev\/null|--save-dev\b|(?<![\w.-])(?:go|web)\.dev(?![\w.-])|\.prod\(/;
my $SELF = '.claude/skills/pr-ready/hygiene-sweep.pl';

my $arg = shift // die "usage: perl hygiene-sweep.pl <base>|-\n";
my $in;
if ($arg eq '-') {
    $in = \*STDIN;
} else {
    # Pin the output format against user config (noprefix, external diff tools, quoted names).
    open($in, '-|', 'git', '-c', 'core.quotePath=false', 'diff', '-U0', '--no-color', '--no-ext-diff',
        '--src-prefix=a/', '--dst-prefix=b/', "$arg...HEAD") or die "git diff failed: $!\n";
}

my (%added, @order, $file, $ln, $left);    # file => [[line, text], ...]; $left = added lines due in the hunk
$left = 0;
while (my $row = <$in>) {
    chomp $row;
    $row =~ s/\r$//;
    if ($arg eq '-') {
        $file //= '<stdin>';
        push @order, $file unless $added{$file};
        push @{ $added{$file} }, [$., $row];
        next;
    }
    if ($left == 0) {    # outside a hunk's added lines, so a "+++" line really is a header
        if ($row =~ m{^\+\+\+ (?:"?b/(.*?)"?\t?|/dev/null)$}) { $file = $1; next; }
        if ($row =~ /^@@ -\S+ \+(\d+)(?:,(\d+))?/) { ($ln, $left) = ($1, $2 // 1); }
        next;
    }
    next unless $row =~ s/^\+//;
    $left--;
    next unless defined $file && $file ne $SELF;
    push @order, $file unless $added{$file};
    push @{ $added{$file} }, [$ln++, $row];
}
close($in) or $arg eq '-' or die "git diff $arg...HEAD failed (fetch the base first?)\n";
die "empty input: nothing to sweep (did the command feeding stdin fail?)\n" if $arg eq '-' && !@order;

my $hits = 0;
for my $f (@order) {
    my @rows = @{ $added{$f} };
    for my $r (@rows) {
        (my $spared = $r->[1]) =~ s/$SPARE/ /g;
        for my $p (@LINE) {
            if (($p->[0] eq 'env' ? $spared : $r->[1]) =~ $p->[1]) { print "$f:$r->[0]: [$p->[0]] $r->[1]\n"; $hits++; }
        }
    }
    # Join consecutive added lines; report only matches that cross a line break.
    my ($text, $prev, @starts) = ('', -1);
    for my $r (@rows) {
        (my $t = $r->[1]) =~ s{^\s*(?://+|/?\*+|#+|--)\s?}{};
        $text .= "\0" if $r->[0] != $prev + 1;    # a gap between hunks is not a line break to join over
        push @starts, [length $text, $r->[0]];
        $text .= "$t\n";
        $prev = $r->[0];
    }
    for my $p (@WRAP) {
        while ($text =~ /$p->[1]/g) {
            my ($s, $m) = ($-[0], $&);
            next unless $m =~ /\n/;
            my ($at) = grep { $_->[0] <= $s } reverse @starts;
            (my $flat = $m) =~ s/\s+/ /g;
            print "$f:$at->[1]: [$p->[0]] $flat\n";
            $hits++;
        }
    }
}
exit($hits ? 1 : 0);
