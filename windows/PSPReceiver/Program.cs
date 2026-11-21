using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace PSPReceiver;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

// Sends keyboard scancodes that match PPSSPP's default keymap.
static class KeySender
{
    const uint EXT = 1, UP = 2, SCAN = 8;
    const int DEAD = 35; // analog -> key threshold (percent)

    // bit, scancode, extended
    static readonly (int bit, ushort scan, bool ext)[] Map =
    {
        (1, 0x48, true),  (2, 0x50, true),  (4, 0x4B, true),  (8, 0x4D, true),   // arrows = D-pad
        (16, 0x2C, false), (32, 0x2D, false), (64, 0x1E, false), (128, 0x1F, false), // Z X A S = Cross Circle Square Triangle
        (256, 0x10, false), (512, 0x11, false),                                  // Q W = L R
        (1024, 0x39, false), (2048, 0x2F, false),                                // Space = Start, V = Select
        (1 << 12, 0x17, false), (1 << 13, 0x25, false),                          // I K = analog up/down
        (1 << 14, 0x24, false), (1 << 15, 0x26, false),                          // J L = analog left/right
    };

    static int current;
    static readonly object Gate = new();

    public static void Apply(int mask, int ax, int ay)
    {
        int want = mask & 0xFFF;
        if (ay < -DEAD) want |= 1 << 12;
        if (ay > DEAD) want |= 1 << 13;
        if (ax < -DEAD) want |= 1 << 14;
        if (ax > DEAD) want |= 1 << 15;
        lock (Gate)
        {
            foreach (var k in Map)
            {
                bool w = (want & k.bit) != 0, h = (current & k.bit) != 0;
                if (w != h) Send(k.scan, k.ext, w);
            }
            current = want;
        }
    }

    static void Send(ushort scan, bool ext, bool down)
    {
        var i = new INPUT
        {
            type = 1,
            u = new INPUTUNION
            {
                ki = new KEYBDINPUT
                {
                    wVk = 0, wScan = scan,
                    dwFlags = SCAN | (ext ? EXT : 0) | (down ? 0 : UP)
                }
            }
        };
        SendInput(1, new[] { i }, Marshal.SizeOf<INPUT>());
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint n, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public INPUTUNION u; }
    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION { [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public MOUSEINPUT mi; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
}

class MainForm : Form
{
    readonly Label wifi = new() { AutoSize = true };
    readonly Label bt = new() { AutoSize = true };
    readonly Label ctl = new() { AutoSize = true };

    public MainForm()
    {
        Text = "PSP Receiver";
        BackColor = Color.FromArgb(16, 16, 20);
        ForeColor = Color.White;
        Size = new Size(520, 300);
        StartPosition = FormStartPosition.CenterScreen;

        var info = new Label
        {
            AutoSize = true,
            ForeColor = Color.Silver,
            Text = "Keep PPSSPP focused (default keymap). Keys sent:\n" +
                   "Arrows = D-pad, Z/X/A/S = Cross/Circle/Square/Triangle,\n" +
                   "Q/W = L/R, Space = Start, V = Select, I/J/K/L = analog."
        };
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, Padding = new Padding(16) };
        panel.Controls.AddRange(new Control[] { wifi, bt, ctl, info });
        Controls.Add(panel);

        wifi.Text = "Wi-Fi: starting…";
        bt.Text = "Bluetooth: starting…";
        ctl.Text = "Controller: none";

        Shown += (_, _) =>
        {
            Run(WifiLoop);
            Run(BtLoop);
        };
        FormClosing += (_, _) => KeySender.Apply(0, 0, 0);
    }

    static void Run(Action a) => new Thread(() => a()) { IsBackground = true }.Start();

    void Set(Label l, string t)
    {
        if (IsHandleCreated) BeginInvoke(() => l.Text = t);
    }

    void WifiLoop()
    {
        try
        {
            var l = new TcpListener(IPAddress.Any, 7777);
            l.Start();
            var ips = Dns.GetHostEntry(Dns.GetHostName()).AddressList
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork)
                .Select(a => a.ToString());
            Set(wifi, "Wi-Fi: enter " + string.Join(" or ", ips) + " on the phone (port 7777)");
            while (true)
            {
                var c = l.AcceptTcpClient();
                c.NoDelay = true;
                Run(() => Handle(c.GetStream(), "Wi-Fi"));
            }
        }
        catch (Exception e) { Set(wifi, "Wi-Fi error: " + e.Message); }
    }

    void BtLoop()
    {
        try
        {
            var l = new BluetoothListener(BluetoothService.SerialPort);
            l.Start();
            Set(bt, "Bluetooth: waiting (pair phone with this PC first)");
            while (true)
            {
                var c = l.AcceptBluetoothClient();
                Run(() => Handle(c.GetStream(), "Bluetooth"));
            }
        }
        catch (Exception e) { Set(bt, "Bluetooth unavailable: " + e.Message); }
    }

    void Handle(Stream s, string who)
    {
        Set(ctl, "Controller: " + who + " connected");
        try
        {
            using var r = new StreamReader(s);
            string? line;
            while ((line = r.ReadLine()) != null)
            {
                var p = line.Split(' ');
                if (p.Length >= 3 && int.TryParse(p[0], out var m) && int.TryParse(p[1], out var ax) && int.TryParse(p[2], out var ay))
                    KeySender.Apply(m, ax, ay);
            }
        }
        catch { }
        finally
        {
            KeySender.Apply(0, 0, 0);
            Set(ctl, "Controller: none");
        }
    }
}
