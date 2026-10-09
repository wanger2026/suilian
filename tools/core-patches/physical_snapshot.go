package easytier

import (
 "net/netip"
 "github.com/easytier/easytier/easytier-go/platform"
 C "github.com/metacubex/mihomo/constant"
)

// Android supplies physical LinkProperties because Go's interface enumeration
// is restricted on modern Android. Never advertise the VPN's virtual addresses.
func PhysicalSnapshot(addresses []string) platform.EnvironmentSnapshot {
 s:=platform.EnvironmentSnapshot{}
 seen:=map[netip.Addr]bool{}
 overlay:=netip.MustParsePrefix("10.144.77.0/24")
 tun:=netip.MustParsePrefix("172.19.0.0/30")
 for _,v:=range addresses {
  a,e:=netip.ParseAddr(v);if e!=nil{continue};a=a.Unmap()
  if !a.IsGlobalUnicast()||a.IsLoopback()||a.IsLinkLocalUnicast()||a.Zone()!=""||overlay.Contains(a)||tun.Contains(a)||seen[a]{continue}
  seen[a]=true;s.LocalIPs=append(s.LocalIPs,a)
  if a.Is4(){s.InterfaceIPv4s=append(s.InterfaceIPv4s,a)}else{s.InterfaceIPv6s=append(s.InterfaceIPv6s,a);if !a.IsPrivate()&&s.PublicIPv6==nil {value:=a;s.PublicIPv6=&value}}
  if len(seen)>=32{break}
 }
 return s
}
func ServicesWithAddresses(d C.Dialer,addresses []string)platform.Services {
 s:=Services(d);s.Snapshot=PhysicalSnapshot(addresses);return s
}
