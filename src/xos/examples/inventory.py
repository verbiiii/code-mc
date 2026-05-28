import mc

item = mc.player.inventory.hand
#mc.chat(str(item))
#print(item)

hand = mc.player.inventory.hand
print(hand.type)
print(type(hand.meta))
#print(hand.meta["GunID"])
#print("hand:", hand)
#print("offhand:", mc.player.inventory.offhand)
#print("hotbar:", mc.player.inventory.hotbar)
#print("armor:", mc.player.inventory.armor)
#print("contents:", mc.player.inventory.contents)
#print("enderchest:", mc.player.inventory.enderchest)

