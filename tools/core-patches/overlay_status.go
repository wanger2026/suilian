package main

import (
 "context"
 "time"
 "github.com/metacubex/mihomo/tunnel"
)

func init() {
 methodHandlers["refreshPhysicalNetwork"] = withArguments(func(addresses *[]string, response MethodResponse) {
  if addresses==nil||len(*addresses)>32{response.failure("invalid","invalid physical addresses",nil);return}
  ctx,cancel:=context.WithTimeout(context.Background(),5*time.Second);defer cancel()
  p:=tunnel.Proxies()["LINK"]
  if p==nil {response.failure("not_ready","connection is not running",nil);return}
  setter,ok:=p.Adapter().(interface{RefreshPhysicalNetwork(context.Context,[]string)error})
  if !ok {response.failure("unsupported","network refresh unavailable",nil);return}
  if err:=setter.RefreshPhysicalNetwork(ctx,*addresses);err!=nil{response.failure("not_ready","network refresh failed",nil);return}
  response.success(true)
 })
 methodHandlers["getOverlayStatus"] = func(_ *MethodCall, response MethodResponse) {
  ctx,cancel:=context.WithTimeout(context.Background(),3*time.Second);defer cancel()
  p:=tunnel.Proxies()["LINK"]
  if p==nil {response.failure("not_ready","connection is not running",nil);return}
  getter,ok:=p.Adapter().(interface{OverlayStatus(context.Context)(map[string]any,error)})
  if !ok {response.failure("unsupported","overlay diagnostics unavailable",nil);return}
  result,err:=getter.OverlayStatus(ctx)
  if err!=nil {response.failure("not_ready","overlay status query failed",nil);return}
  response.success(result)
 }
}
