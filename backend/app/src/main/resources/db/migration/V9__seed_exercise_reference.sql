-- =============================================================================
-- FitAgent P1 迁移 V9：动作字典种子（25 条）
-- 依赖 V5。设计包 schema.sql 没有 exercise_reference 的种子，本轮必须自备，
-- 否则页面无可选项、导入模板也无法"真实可导入"。
--
-- 两条刻意的取舍：
--   1) source_url 与 instructions 全部留 NULL。不引入未经来源核对的动作说明文本，
--      也不编造权威链接。是否补录由维护者审核后决定。
--   2) 不显式指定 id，交给自增分配，避免占位 ID 与后续场景冲突。
--      重复执行由 Flyway 历史表保证一次性；手工执行本文件前请先确认字典为空。
--
-- category 决定导入/编辑时的字段校验矩阵（见 docs/p1-plan-import.md §5），不是展示用标签。
-- =============================================================================

INSERT INTO `exercise_reference` (`exercise_code`, `name`, `category`, `muscle_groups`, `equipment`, `status`) VALUES
-- 力量 13 条
('BARBELL_BACK_SQUAT',        '杠铃深蹲',     'STRENGTH', JSON_ARRAY('股四头肌','臀大肌','腘绳肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_DEADLIFT',          '杠铃硬拉',     'STRENGTH', JSON_ARRAY('腘绳肌','臀大肌','竖脊肌'),        JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_BENCH_PRESS',       '杠铃卧推',     'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('BARBELL','BENCH'),  'ACTIVE'),
('BARBELL_OVERHEAD_PRESS',    '杠铃站姿肩上推','STRENGTH', JSON_ARRAY('三角肌','肱三头肌','斜方肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_ROW',               '杠铃划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('DUMBBELL_BENCH_PRESS',      '哑铃卧推',     'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('DUMBBELL','BENCH'), 'ACTIVE'),
('DUMBBELL_ROW',              '哑铃划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('DUMBBELL','BENCH'), 'ACTIVE'),
('DUMBBELL_REVERSE_LUNGE',    '哑铃后撤步弓步','STRENGTH', JSON_ARRAY('股四头肌','臀大肌','腘绳肌'),      JSON_ARRAY('DUMBBELL'),         'ACTIVE'),
('LAT_PULLDOWN',              '高位下拉',     'STRENGTH', JSON_ARRAY('背阔肌','肱二头肌'),                JSON_ARRAY('CABLE_MACHINE'),    'ACTIVE'),
('SEATED_CABLE_ROW',          '坐姿绳索划船', 'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('CABLE_MACHINE'),    'ACTIVE'),
('LEG_PRESS',                 '腿举',         'STRENGTH', JSON_ARRAY('股四头肌','臀大肌'),                JSON_ARRAY('MACHINE'),          'ACTIVE'),
('PUSH_UP',                   '俯卧撑',       'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('BODYWEIGHT'),       'ACTIVE'),
('INVERTED_ROW',              '反向划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('BODYWEIGHT'),       'ACTIVE'),
-- 心肺 7 条
('RUNNING',                   '跑步',         'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('OUTDOOR','TREADMILL'), 'ACTIVE'),
('TREADMILL_WALK_INCLINE',    '坡度快走',     'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('TREADMILL'),        'ACTIVE'),
('CYCLING',                   '骑行',         'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('BIKE'),             'ACTIVE'),
('ROWING',                    '划船机',       'CARDIO',   JSON_ARRAY('下肢肌群','背阔肌','心肺'),          JSON_ARRAY('ROWER'),            'ACTIVE'),
('ELLIPTICAL',                '椭圆机',       'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('ELLIPTICAL'),       'ACTIVE'),
('STAIR_CLIMBER',             '楼梯机',       'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('STAIR_CLIMBER'),    'ACTIVE'),
('JUMP_ROPE',                 '跳绳',         'CARDIO',   JSON_ARRAY('小腿','肩部','心肺'),                JSON_ARRAY('JUMP_ROPE'),        'ACTIVE'),
-- 柔韧与活动度 5 条
('CAT_COW',                   '猫牛式',       'MOBILITY', JSON_ARRAY('脊柱','背部'),                      JSON_ARRAY('MAT'),              'ACTIVE'),
('HIP_FLEXOR_STRETCH',        '髋屈肌拉伸',   'MOBILITY', JSON_ARRAY('髂腰肌','股直肌'),                  JSON_ARRAY('MAT'),              'ACTIVE'),
('HAMSTRING_STRETCH',         '腘绳肌拉伸',   'MOBILITY', JSON_ARRAY('腘绳肌'),                            JSON_ARRAY('MAT'),              'ACTIVE'),
('SHOULDER_CROSS_STRETCH',    '肩部横位拉伸', 'MOBILITY', JSON_ARRAY('三角肌','上背'),                    JSON_ARRAY('MAT'),              'ACTIVE'),
('THORACIC_ROTATION',         '胸椎旋转',     'MOBILITY', JSON_ARRAY('胸椎','核心'),                      JSON_ARRAY('MAT'),              'ACTIVE');
