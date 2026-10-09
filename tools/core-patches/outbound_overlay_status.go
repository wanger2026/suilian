//go:build !no_easytier

package outbound

import (
 "context"
 "encoding/json"
 "errors"
 "time"
)

func (p *autoCloseProxyAdapter) OverlayStatus(ctx context.Context) (map[string]any,error) {
 getter,ok:=p.ProxyAdapter.(interface{OverlayStatus(context.Context)(map[string]any,error)})
 if !ok{return nil,errors.New("not an overlay adapter")}
 return getter.OverlayStatus(ctx)
}

// OverlayStatus exports an explicit allowlist, never identity/configuration keys.
func (e *EasyTier) OverlayStatus(ctx context.Context) (map[string]any, error) {
 i,err:=e.currentInstance();if err!=nil{return nil,err}
 peers,err:=i.ListPeer(ctx);if err!=nil{return nil,err}
 routes,err:=i.ListRoute(ctx);if err!=nil{return nil,err}
 result:=map[string]any{"peerCount":len(peers),"routeCount":len(routes)}
 e.mu.Lock();result["events"]=append([]map[string]any(nil),e.recentEvents...);e.mu.Unlock()
 if node,nodeErr:=i.ShowNodeInfo(ctx);nodeErr==nil {
  raw,_:=json.Marshal(node);var m map[string]any;json.Unmarshal(raw,&m)
  result["stun"]=m["stun_info"]
 }
 safePeers:=[]any{};safeRoutes:=[]any{}
 for n,p:=range peers {
  if n>=32{break};raw,_:=json.Marshal(p);var m map[string]any;json.Unmarshal(raw,&m)
  entry:=map[string]any{"peerId":m["peer_id"]};conns:=[]any{}
  if all,ok:=m["conns"].([]any);ok {for _,c:=range all {v,ok:=c.(map[string]any);if !ok{continue};conns=append(conns,map[string]any{"stats":v["stats"],"isClient":v["is_client"],"closed":v["is_closed"]})}}
  entry["connections"]=conns;safePeers=append(safePeers,entry)
 }
 for n,r:=range routes {
  if n>=64{break};raw,_:=json.Marshal(r);var m map[string]any;json.Unmarshal(raw,&m)
  safeRoutes=append(safeRoutes,map[string]any{"peerId":m["peer_id"],"ipv4":m["ipv4_addr"],"nextHop":m["next_hop_peer_id"],"cost":m["cost"]})
 }
 result["peers"]=safePeers;result["routes"]=safeRoutes
 return result,nil
}

func (e *EasyTier) recordNetworkEvent(kind string) {
 e.mu.Lock();defer e.mu.Unlock()
 e.recentEvents=append(e.recentEvents,map[string]any{"time":time.Now().UnixMilli(),"kind":kind})
 if len(e.recentEvents)>60 {e.recentEvents=e.recentEvents[len(e.recentEvents)-60:]}
}

func (p *autoCloseProxyAdapter) RefreshPhysicalNetwork(ctx context.Context, addresses []string) error {
 setter,ok:=p.ProxyAdapter.(interface{RefreshPhysicalNetwork(context.Context,[]string)error})
 if !ok{return errors.New("not an overlay adapter")};return setter.RefreshPhysicalNetwork(ctx,addresses)
}
func (e *EasyTier) RefreshPhysicalNetwork(ctx context.Context, addresses []string) error {
 if len(addresses)>32{return errors.New("too many addresses")}
 e.mu.Lock()
 e.option.InterfaceAddresses=append([]string(nil),addresses...)
 instance:=e.instance
 e.mu.Unlock()
 // Close this instance (Stop leaves its event stream open); its existing loop closes the old host and creates
 // a fresh snapshot and STUN state. Android's TUN and direct rules stay alive.
 if instance!=nil{return instance.Close(ctx)}
 return nil
}
