package libcore

import (
	"log"
	"path/filepath"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
}

func (g *geosite) Open(path string) error {
	geositeReader, _, err := geosites.Open(path)
	g.geositeReader = geositeReader
	return err
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		// Kombain: "geosite:ru" style codes live under "category-<code>" in sing-geosite
		if alt, altErr := g.geositeReader.Read("category-" + code); altErr == nil {
			sourceSet, err = alt, nil
		}
	}
	if err != nil {
		// Kombain: an unknown category must not take the whole VPN down — match nothing instead
		log.Println("geosite: unknown code", code, "- rule skipped")
		return []option.HeadlessRule{{
			Type:           C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{Domain: []string{"kombain-missing-geosite.invalid"}},
		}}, nil
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geosite)
		if err := g.Open(filepath.Join(externalAssetsPath, "geosite.db")); err != nil {
			return nil, err
		}
		return g.Rules(name)
	}
}
