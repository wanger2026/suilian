package easytier
import "testing"
func TestPhysicalSnapshotExcludesTunAndUnsafeAddresses(t *testing.T){
 s:=PhysicalSnapshot([]string{"192.0.2.111","192.0.2.111","10.144.77.2","172.19.0.1","127.0.0.1","0.0.0.0","ff02::1","fe80::1","bad","2409:1234::1"})
 if len(s.LocalIPs)!=2||len(s.InterfaceIPv4s)!=1||len(s.InterfaceIPv6s)!=1||s.PublicIPv6==nil{t.Fatalf("unexpected snapshot: %+v",s)}
}
func TestPhysicalSnapshotBoundsAndPrivateIPv6(t *testing.T){
 s:=PhysicalSnapshot([]string{"fdab::1"});if s.PublicIPv6!=nil||len(s.InterfaceIPv6s)!=1{t.Fatal("private IPv6 presented as public")}
}
