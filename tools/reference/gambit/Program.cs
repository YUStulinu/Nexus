using System.Globalization;
using System.Text;
using Gambit.Core.Games;
using Gambit.Core.Neural;
using Gambit.Core.Search;

// Reference outputs of Gambit's C# network and search, for checking the Java port in NEXUS.
CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
string models = args[0];
var sb = new StringBuilder("{\n");

void Net<TGame>(string file, string[] positions, Func<string, TGame> make) where TGame : struct, IGame<TGame>
{
    var net = NativeNetwork.Load(Path.Combine(models, file));
    sb.Append($"  \"{TGame.Name}\": [\n");
    for (int i = 0; i < positions.Length; i++)
    {
        var g = make(positions[i]);
        var planes = new float[TGame.PlaneCount * TGame.Height * TGame.Width];
        g.Encode(planes);
        var logits = new float[TGame.ActionCount];
        float v = net.Evaluate(planes, logits);
        // A deterministic search: 400 simulations, one leaf at a time.
        var tree = new SearchTree<TGame>(SearchConfig.Play, 1);
        tree.Reset(g);
        var ev = net.For<TGame>();
        var lg = new float[TGame.ActionCount]; var vals = new float[1];
        int sims = 0;
        while (sims < 400 && !g.Outcome.IsOver())
        {
            var kind = tree.SelectLeaf(out int leaf, out var st);
            if (kind == LeafKind.NeedsEvaluation)
            {
                ev.Evaluate(new[] { st }, lg, vals);
                tree.Expand(leaf, st, lg, vals[0]);
            }
            sims++;
        }
        var visits = new int[TGame.ActionCount];
        foreach (var s in tree.RootStats()) visits[s.Action] = s.Visits;
        sb.Append($"    {{\"moves\": \"{positions[i]}\", \"value\": {v:R}, \"logits\": [{string.Join(", ", logits.Select(x => x.ToString("R")))}], " +
                  $"\"visits400\": [{string.Join(", ", visits)}], \"rootValue400\": {tree.RootValue:R}}}{(i + 1 < positions.Length ? "," : "")}\n");
    }
    sb.Append("  ]");
}

Net<ConnectFour>("connect4.gnet", new[] { "", "4", "44", "4453", "444333", "1234567", "4455661", "32164625" }, ConnectFour.FromMoves);
sb.Append(",\n");
Net<Gomoku>("gomoku.gnet", new[] { "", "e5", "e5 d4", "e5 d4 e4 e6 f5" }, s =>
{
    var g = Gomoku.Initial;
    foreach (var m in s.Split(' ', StringSplitOptions.RemoveEmptyEntries)) g.Play(g.ParseAction(m));
    return g;
});
sb.Append("\n}\n");
Console.Write(sb.ToString());
