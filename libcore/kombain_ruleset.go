package libcore

import (
	"bufio"
	"os"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// CompileDomainList turns a domain list ("domain:x.com" / "full:x.com" / "x.com" per line)
// into a binary sing-box rule-set. Returns the number of domains.
// Kombain uses it for the «только заблокированное» list: a binary rule-set loads several times
// faster than the JSON source form.
func CompileDomainList(srcPath, dstPath string) (int32, error) {
	in, err := os.Open(srcPath)
	if err != nil {
		return 0, err
	}
	defer in.Close()
	var suffix, full []string
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if i := strings.IndexByte(line, ' '); i > 0 {
			line = line[:i]
		}
		switch {
		case strings.HasPrefix(line, "full:"):
			full = append(full, strings.TrimPrefix(line, "full:"))
		case strings.HasPrefix(line, "domain:"):
			suffix = append(suffix, strings.TrimPrefix(line, "domain:"))
		case strings.Contains(line, ":"):
			// regexp: / keyword: are not used by this list
		default:
			suffix = append(suffix, line)
		}
	}
	if err = sc.Err(); err != nil {
		return 0, err
	}
	var rules []option.HeadlessRule
	if len(suffix) > 0 {
		rules = append(rules, option.HeadlessRule{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultHeadlessRule{DomainSuffix: suffix}})
	}
	if len(full) > 0 {
		rules = append(rules, option.HeadlessRule{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultHeadlessRule{Domain: full}})
	}
	tmp := dstPath + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return 0, err
	}
	err = srs.Write(out, option.PlainRuleSet{Rules: rules}, C.RuleSetVersion2)
	closeErr := out.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		os.Remove(tmp)
		return 0, err
	}
	return int32(len(suffix) + len(full)), os.Rename(tmp, dstPath)
}
