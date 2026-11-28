package telegramrelay

import (
	"bytes"
	"encoding/xml"
	"html/template"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestTelegramHTMLMessage(t *testing.T) {
	source, err := os.ReadFile("message.tmpl")
	if err != nil {
		t.Fatal(err)
	}
	renderer, err := template.New("telegram").Option("missingkey=zero").
		Funcs(template.FuncMap{"toUpper": strings.ToUpper}).Parse(string(source))
	if err != nil {
		t.Fatal(err)
	}
	families := map[string]struct {
		dashboard string
		panel     int
	}{
		"CabinetHTTPSUnavailable":            {"cabinet-platform", 4},
		"CabinetServiceDown":                 {"credit-pipeline", 1},
		"CabinetHTTPHighErrorRate":           {"cabinet-platform", 2},
		"CabinetHTTPHighLatency":             {"cabinet-platform", 3},
		"CabinetPVCLowSpace":                 {"cabinet-platform", 7},
		"CabinetNodeDiskLowSpace":            {"cabinet-platform", 7},
		"CabinetAssistantFallbackElevated":   {"cabinet-agent-final", 5},
		"CabinetAssistantAnswerErrors":       {"cabinet-agent-final", 6},
		"CabinetNotificationDeliveryFailed":  {"cabinet-platform", 15},
		"CabinetNotificationDeliveryDelayed": {"cabinet-platform", 12},
		"CabinetFailedJobs":                  {"credit-pipeline", 2},
		"CabinetQueueDelayed":                {"credit-pipeline", 4},
		"CabinetMetricsStale":                {"credit-pipeline", 12},
		"CabinetSourceCircuitOpen":           {"credit-pipeline", 8},
		"CabinetTelegramSmoke":               {},
	}
	type alert struct{ StartsAt, EndsAt time.Time }
	type notification struct {
		Receiver, Status                             string
		GroupLabels, CommonLabels, CommonAnnotations map[string]string
		Alerts                                       []alert
	}
	type document struct {
		Links []struct {
			Href string `xml:"href,attr"`
		} `xml:"a"`
	}
	latest := time.Date(2026, 11, 27, 1, 11, 0, 0, time.UTC)
	earliest := latest.Add(-12 * time.Minute)
	from := strconv.FormatInt(earliest.Add(-15*time.Minute).UnixMilli(), 10)
	for env, base := range map[string]string{
		"dev":  "https://dev.credit-cabinet.ru/grafana",
		"prod": "https://grafana.credit-cabinet.ru",
	} {
		for _, status := range []string{"firing", "resolved"} {
			for name, family := range families {
				t.Run(env+"/"+status+"/"+name, func(t *testing.T) {
					to := "now"
					if status == "resolved" {
						to = strconv.FormatInt(latest.UnixMilli(), 10)
					}
					service := "internal.example.invalid <private>"
					if name == "CabinetServiceDown" {
						service = "credit"
					}
					data := notification{
						Receiver: "telegram-" + env, Status: status,
						GroupLabels:       map[string]string{"alertname": name},
						CommonLabels:      map[string]string{"severity": "critical", "target": "cabinet-public", "service": service, "instance": "10.0.0.1"},
						CommonAnnotations: map[string]string{"summary": "PRIVATE-ANNOTATION"},
						Alerts:            []alert{{earliest.Add(time.Minute), latest.Add(-time.Minute)}, {earliest, latest}},
					}
					var out bytes.Buffer
					if err := renderer.Execute(&out, data); err != nil {
						t.Fatal(err)
					}
					m := out.String()
					if name == "CabinetTelegramSmoke" {
						want := "[PUMP TEST][" + strings.ToUpper(status) + "][" + env + "][info] CabinetTelegramSmoke monitoring"
						if m != want {
							t.Fatalf("smoke = %q, want %q", m, want)
						}
						return
					}
					if !strings.Contains(m, "<a href=") || !strings.Contains(m, "Где:") {
						t.Fatalf("missing literal anchor or scope: %q", m)
					}
					for _, bad := range []string{"&lt;a", "&amp;amp;", "#ZgotmplZ", "10.0.0.1", "PRIVATE-ANNOTATION", "internal.example.invalid"} {
						if strings.Contains(m, bad) {
							t.Fatalf("unexpected %q in %q", bad, m)
						}
					}
					if name == "CabinetServiceDown" && !strings.Contains(m, " / credit") {
						t.Fatal("missing allowlisted service")
					}
					if name == "CabinetHTTPSUnavailable" && !strings.Contains(m, "/api/v1/products") {
						t.Fatal("missing public probe scope")
					}
					var parsed document
					if err := xml.Unmarshal([]byte("<document>"+m+"</document>"), &parsed); err != nil {
						t.Fatal(err)
					}
					if len(parsed.Links) != 1 {
						t.Fatalf("found %d anchors", len(parsed.Links))
					}
					link := parsed.Links[0].Href
					u, err := url.Parse(link)
					if err != nil || u.Scheme != "https" {
						t.Fatalf("not an HTTPS href: %q", link)
					}
					want := base + "/d/" + family.dashboard + "?from=" + from + "&to=" + to + "&viewPanel=" + strconv.Itoa(family.panel)
					if link != want {
						t.Fatalf("href = %q, want %q", link, want)
					}
				})
			}
		}
	}
}
