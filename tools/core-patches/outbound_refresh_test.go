//go:build !no_easytier
package outbound
import (
 "context"
 "testing"
 "time"
 "github.com/metacubex/mihomo/component/dialer"
 "github.com/metacubex/mihomo/component/easytier"
)
func TestPhysicalNetworkRefreshRecreatesInstance(t *testing.T){
 ctx,cancel:=context.WithTimeout(context.Background(),12*time.Second);defer cancel()
 noListener,encrypted:=true,true
 option:=EasyTierOption{Name:"refresh-test",NetworkName:"isolated-refresh",NetworkSecret:"synthetic-test-secret-not-a-real-credential",IPv4:"10.179.0.2/24",Peers:[]string{"tcp://127.0.0.1:9"},NoListener:&noListener,EnableEncryption:&encrypted}
 config,err:=option.structuredConfig().RenderTOML();if err!=nil{t.Fatal(err)}
 e:=&EasyTier{Base:&Base{name:option.Name,dialer:dialer.NewDialer()},option:option,configTOML:easytier.ApplyRequiredFlags(config),stateDir:t.TempDir(),ctx:ctx,cancel:cancel}
 defer e.Close()
 if err=e.ensureStarted(ctx);err!=nil{t.Fatal(err)}
 before,err:=e.currentInstance();if err!=nil{t.Fatal(err)}
 if err=e.RefreshPhysicalNetwork(ctx,[]string{"192.168.100.20"});err!=nil{t.Fatal(err)}
 deadline:=time.After(8*time.Second)
 for{
  after,_:=e.currentInstance()
  if after!=nil&&after!=before {if err=e.ensureStarted(ctx);err!=nil{t.Fatal(err)};return}
  select {case <-deadline:t.Fatal("network refresh did not recreate instance; stopped event stream may still be open");case <-time.After(30*time.Millisecond):}
 }
}
