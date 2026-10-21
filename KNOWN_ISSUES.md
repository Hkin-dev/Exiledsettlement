# 0.18.1 known issues / 已知问题

1. **拥挤的小牧场仍可能卡住。** 发布复验中，6×6 牧场、三只成年牛、牧民从栏外开始工作的场景出现超时：动物离栏后，牧民在捕获与领取牵引绳的流程中未能继续推进。相同测试单独运行时完成了屠宰和交仓，但这不能证明问题已经修复。该问题保留在 0.18.1 中；本次发布没有改变 test18 的运行逻辑。

1. **Work can still stall in crowded small pastures.** Release verification timed out in a 6×6 pasture with three adult cows and a herder starting outside the pen. After an animal left the pen, the herder did not progress through capture and lead collection. The same test completed slaughter and delivery when run individually, which does not establish that the issue is fixed. This limitation remains in 0.18.1; the release preserves the runtime behavior of test18.

These are isolated server test observations, not a guarantee of behavior in every player world. / 以上为隔离服务器的测试观察，不代表所有玩家存档的表现。
