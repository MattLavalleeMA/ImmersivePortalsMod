import json

data = json.load(open('migration_tools/reports/compile_errors.json', encoding='utf-8'))
errs = data if isinstance(data, list) else data.get('errors', data)
files = [
    'ImmPtlChunkTickets.java', 'PlayerChunkLoading.java', 'ServerTeleportationManager.java',
    'ClientTeleportationManager.java', 'ImplRemoteProcedureCall.java', 'CHelper.java',
    'ImmPtlNetworkConfig.java', 'MixinMinecraftServer_Misc.java', 'MixinMinecraft_RedirectedPacket.java',
    'MixinClientPacketListener.java', 'MixinServerGamePacketListenerImpl.java',
]
seen = set()
for e in errs:
    f = e.get('file', '')
    for name in files:
        if name in f:
            key = (name, e.get('line'), e.get('message'))
            if key not in seen:
                seen.add(key)
                print(name, e.get('line'), '|', e.get('message'))
