package net.runelite.client.plugins.microbot.agility.courses;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.agility.models.AgilityObstacleModel;
import net.runelite.client.plugins.microbot.util.misc.Operation;

public class CanafisCourse implements AgilityCourseHandler
{
	@Override
	public WorldPoint getStartPoint()
	{
		return new WorldPoint(3507, 3489, 0);
	}

	@Override
	public List<AgilityObstacleModel> getObstacles()
	{
		return List.of(
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_START_TREE),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_JUMP),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_JUMP_2, 3498, -1, Operation.GREATER_EQUAL, Operation.GREATER),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_JUMP_5),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_JUMP_3),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_POLEVAULT),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_JUMP_4),
			new AgilityObstacleModel(ObjectID.ROOFTOPS_CANIFIS_LEAPDOWN)
		);
	}

	@Override
	public Integer getRequiredLevel()
	{
		return 40;
	}
}
