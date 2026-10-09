using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

class CardPanel : Panel {
    public CardPanel(){DoubleBuffered=true;BackColor=Color.White;Padding=new Padding(22);}
    protected override void OnResize(EventArgs e){base.OnResize(e);using(var p=Branding.Round(new RectangleF(0,0,Width,Height),18)){var previous=Region;Region=new Region(p);if(previous!=null)previous.Dispose();}}
}
static class Branding {
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr handle);
    public static GraphicsPath Round(RectangleF r,float radius){var p=new GraphicsPath();float d=radius*2;p.AddArc(r.X,r.Y,d,d,180,90);p.AddArc(r.Right-d,r.Y,d,d,270,90);p.AddArc(r.Right-d,r.Bottom-d,d,d,0,90);p.AddArc(r.X,r.Bottom-d,d,d,90,90);p.CloseFigure();return p;}
    public static Bitmap Mark(int size){
        var b=new Bitmap(size,size);using(var g=Graphics.FromImage(b)){
            g.SmoothingMode=SmoothingMode.AntiAlias;g.ScaleTransform(size/108f,size/108f);
            using(var bg=Round(new RectangleF(0,0,108,108),24))using(var fill=new SolidBrush(Color.FromArgb(47,100,237)))g.FillPath(fill,bg);
            using(var path=new GraphicsPath())using(var pen=new Pen(Color.White,9)){
                pen.StartCap=pen.EndCap=LineCap.Round;pen.LineJoin=LineJoin.Round;
                path.AddLine(49,33,43,27);path.AddBezier(43,27,33,18,19,32,27,42);path.AddLine(27,42,40,56);path.AddBezier(40,56,48,64,61,57,58,48);g.DrawPath(pen,path);
                path.Reset();pen.Color=Color.FromArgb(114,228,205);path.AddLine(59,75,65,81);path.AddBezier(65,81,75,90,89,76,81,66);path.AddLine(81,66,68,52);path.AddBezier(68,52,60,44,47,51,50,60);g.DrawPath(pen,path);
            }
        }return b;
    }
    public static Icon AppIcon(){using(var b=Mark(64)){var handle=b.GetHicon();try{return (Icon)Icon.FromHandle(handle).Clone();}finally{DestroyIcon(handle);}}}
#if BUILD_ICON
    [STAThread] static void Main(string[] args){using(var icon=AppIcon())using(var f=File.Create(args[0]))icon.Save(f);}
#endif
}
